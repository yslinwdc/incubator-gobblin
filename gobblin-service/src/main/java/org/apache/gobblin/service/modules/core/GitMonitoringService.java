/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.FS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AbstractIdleService;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.typesafe.config.Config;

import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.password.PasswordManager;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.util.Either;
import org.apache.gobblin.util.ExecutorsUtils;
import org.apache.gobblin.util.PullFileLoader;


@Slf4j
public abstract class GitMonitoringService extends AbstractIdleService {
  private static final String REMOTE_NAME = "origin";
  private static final int TERMINATION_TIMEOUT = 30;

  static final String JAVA_PROPS_EXTENSIONS = "javaPropsExtensions";
  static final String HOCON_FILE_EXTENSIONS = "hoconFileExtensions";
  static final String SHOULD_CHECKPOINT_HASHES = "shouldCheckpointHashes";

  private final Integer pollingInterval;
  private final ScheduledExecutorService scheduledExecutor;

  private String privateKeyPath;
  private String passphrase;
  private boolean isJschLoggerEnabled;

  final GitMonitoringService.GitRepository gitRepo;
  final String repositoryDir;
  final String folderName;
  final PullFileLoader pullFileLoader;
  final Set<String> javaPropsExtensions;

  protected volatile boolean isActive = false;

  GitMonitoringService(Config config) {
    Preconditions.checkArgument(config.hasPath(ConfigurationKeys.GIT_MONITOR_REPO_URI),
        ConfigurationKeys.GIT_MONITOR_REPO_URI + " needs to be specified.");

    String repositoryUri = config.getString(ConfigurationKeys.GIT_MONITOR_REPO_URI);
    this.repositoryDir = config.getString(ConfigurationKeys.GIT_MONITOR_REPO_DIR);
    String branchName = config.getString(ConfigurationKeys.GIT_MONITOR_BRANCH_NAME);
    this.pollingInterval = config.getInt(ConfigurationKeys.GIT_MONITOR_POLLING_INTERVAL);
    this.folderName = config.getString(ConfigurationKeys.GIT_MONITOR_CONFIG_BASE_DIR);
    boolean shouldCheckpointHashes = ConfigUtils.getBoolean(config, SHOULD_CHECKPOINT_HASHES, true);

    PasswordManager passwordManager = PasswordManager.getInstance(ConfigUtils.configToState(config));
    Either<CredentialsProvider, SshSessionFactory> providerSessionFactoryEither;
    boolean isSshWithPublicKeyEnabled = ConfigUtils.getBoolean(config, ConfigurationKeys.GIT_MONITOR_SSH_WITH_PUBLIC_KEY_ENABLED, false);
    if (isSshWithPublicKeyEnabled) {
      this.privateKeyPath = ConfigUtils.getString(config, ConfigurationKeys.GIT_MONITOR_PRIVATE_KEY_PATH, null);
      if (Strings.isNullOrEmpty(this.privateKeyPath)) {
        throw new RuntimeException("Path to private key must be provided");
      }
      String passPhraseEnc = ConfigUtils.getString(config, ConfigurationKeys.GIT_MONITOR_SSH_PASSPHRASE, null);
      if (passPhraseEnc != null) {
        this.passphrase = passwordManager.readPassword(passPhraseEnc);
      }
      providerSessionFactoryEither = Either.right(getSshSessionFactory());
      this.isJschLoggerEnabled = ConfigUtils.getBoolean(config, ConfigurationKeys.GIT_MONITOR_JSCH_LOGGER_ENABLED, false);
    } else { //Use CredentialsProvider
      String username = ConfigUtils.getString(config, ConfigurationKeys.GIT_MONITOR_USERNAME, null);
      String passwordEnc = ConfigUtils.getString(config, ConfigurationKeys.GIT_MONITOR_PASSWORD, null);
      String password = null;
      if (passwordEnc != null) {
        password = passwordManager.readPassword(passwordEnc);
      }
      CredentialsProvider credentialsProvider;
      //Instantiate CredentialsProvider if username/password is provided.
      if (!Strings.isNullOrEmpty(username) && !Strings.isNullOrEmpty(password)) {
        credentialsProvider = new UsernamePasswordCredentialsProvider(username, password);
      } else {
        credentialsProvider = CredentialsProvider.getDefault();
      }
      providerSessionFactoryEither = Either.left(credentialsProvider);
    }

    try {
      this.gitRepo = new GitMonitoringService.GitRepository(repositoryUri, repositoryDir, branchName, providerSessionFactoryEither,
          shouldCheckpointHashes);
    } catch (GitAPIException | IOException e) {
      throw new RuntimeException("Could not open git repository", e);
    }

    Path folderPath = new Path(this.repositoryDir, this.folderName);
    this.javaPropsExtensions = Sets.newHashSet(config.getString(JAVA_PROPS_EXTENSIONS).split(","));
    Set<String> hoconFileExtensions = Sets.newHashSet(config.getString(HOCON_FILE_EXTENSIONS).split(","));
    try {
      this.pullFileLoader = new PullFileLoader(folderPath,
          FileSystem.get(URI.create(ConfigurationKeys.LOCAL_FS_URI), new Configuration()),
          this.javaPropsExtensions, hoconFileExtensions);
    } catch (IOException e) {
      throw new RuntimeException("Could not create pull file loader", e);
    }

    this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
        ExecutorsUtils.newThreadFactory(Optional.of(log), Optional.of("FetchGitConfExecutor")));
  }

  public synchronized void setActive(boolean isActive) {
    if (this.isActive == isActive) {
      // No-op if already in correct state
      return;
    }

    this.isActive = isActive;
  }

  /** Start the service. */
  @Override
  protected void startUp() {
    log.info("Starting the " + getClass().getSimpleName());
    log.info("Polling git with interval {} ", this.pollingInterval);

    // Schedule the job config fetch task
    this.scheduledExecutor.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        try {
          if (shouldPollGit()) {
            processGitConfigChanges();
          }
        } catch (GitAPIException | IOException e) {
          log.error("Failed to process git config changes", e);
          // next run will try again since errors could be intermittent
        }
      }
    }, 0, this.pollingInterval, TimeUnit.SECONDS);
  }

  /**
   * Fetch the list of changes since the last refresh of the repository
   * @throws GitAPIException
   * @throws IOException
   */
  @VisibleForTesting
  void processGitConfigChanges() throws GitAPIException, IOException {
    List<DiffEntry> changes = this.gitRepo.getChanges();
    if (!changes.isEmpty()) {
      processGitConfigChangesHelper(changes);
    }
  }

  /**
   * A helper method where actual processing of the list of changes since the last refresh of the repository takes place
   * and the changes applied.
   * @throws IOException
   */
  void processGitConfigChangesHelper(List<DiffEntry> changes) throws IOException {
    for (DiffEntry change : changes) {
      switch (change.getChangeType()) {
        case ADD:
        case MODIFY:
          addChange(change);
          break;
        case DELETE:
          removeChange(change);
          break;
        case RENAME:
          removeChange(change);
          addChange(change);
          break;
        default:
          throw new RuntimeException("Unsupported change type " + change.getChangeType());
      }
    }

    // Done processing changes, so checkpoint
    this.gitRepo.moveCheckpointAndHashesForward();
  }

  /** Stop the service. */
  @Override
  protected void shutDown() throws Exception {
    this.scheduledExecutor.shutdown();
    this.scheduledExecutor.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
  }


  /**
   * Class for managing a git repository
   */
  static class GitRepository {
    private final static String CHECKPOINT_FILE = "checkpoint.txt";
    private final static String CHECKPOINT_FILE_TMP = "checkpoint.tmp";
    private final String repoUri;
    private final String repoDir;
    private final String branchName;
    private final boolean shouldCheckpointHashes;
    private final Either<CredentialsProvider, SshSessionFactory> providerSessionFactoryEither;

    private Git git;
    private String lastProcessedGitHash;
    private String latestGitHash;

    /**
     * Create an object to manage the git repository stored locally at repoDir with a repository URI of repoDir
     * @param repoUri URI of repository
     * @param repoDir Directory to hold the local copy of the repository
     * @param branchName Branch name
     * @param providerSessionFactoryEither Either {@link UsernamePasswordCredentialsProvider} or {@link SshSessionFactory}
     * @param shouldCheckpointHashes a boolean to determine whether to checkpoint commit hashes
     * @throws GitAPIException
     * @throws IOException
     */
    GitRepository(String repoUri, String repoDir, String branchName, Either<CredentialsProvider, SshSessionFactory>
        providerSessionFactoryEither, boolean shouldCheckpointHashes) throws GitAPIException, IOException {
      this.repoUri = repoUri;
      this.repoDir = repoDir;
      this.branchName = branchName;
      this.providerSessionFactoryEither = providerSessionFactoryEither;
      this.shouldCheckpointHashes = shouldCheckpointHashes;

      initRepository();
    }

    /**
     * Open the repository if it exists locally, otherwise clone it
     * @throws GitAPIException
     * @throws IOException
     */
    private void initRepository() throws GitAPIException, IOException {
      File repoDirFile = new File(this.repoDir);

      try {
        this.git = Git.open(repoDirFile);

        String uri = this.git.getRepository().getConfig().getString("remote", REMOTE_NAME, "url");

        if (!uri.equals(this.repoUri)) {
          throw new RuntimeException("Repo at " + this.repoDir + " has uri " + uri + " instead of " + this.repoUri);
        }
      } catch (RepositoryNotFoundException e) {
        // if the repository was not found then clone a new one
        this.git = Git.cloneRepository()
            .setDirectory(repoDirFile)
            .setURI(this.repoUri)
            .setBranch(this.branchName)
            .setTransportConfigCallback(buildTransportConfigCallback())
            .setCredentialsProvider(getCredentialsProvider())
            .call();
      }

      try {
        this.lastProcessedGitHash = readCheckpoint();
      } catch (FileNotFoundException e) {
        // if no checkpoint is available then start with the first commit
        Iterable<RevCommit> logs = git.log().call();
        RevCommit lastLog = null;

        for (RevCommit log : logs) {
          lastLog = log;
        }

        if (lastLog != null) {
          this.lastProcessedGitHash = lastLog.getName();
        }
      }

      this.latestGitHash = this.lastProcessedGitHash;
    }

    /**
     * Read the last processed commit githash from the checkpoint file
     * @return
     * @throws IOException
     */
    private String readCheckpoint() throws IOException {
      File checkpointFile = new File(this.repoDir, CHECKPOINT_FILE);
      return Files.toString(checkpointFile, Charsets.UTF_8);
    }

    /**
     * Write the last processed commit githash to the checkpoint file
     * @param gitHash
     * @throws IOException
     */
    private void writeCheckpoint(String gitHash) throws IOException {
      // write to a temporary name then rename to make the operation atomic when the file system allows a file to be
      // replaced
      File tmpCheckpointFile = new File(this.repoDir, CHECKPOINT_FILE_TMP);
      File checkpointFile = new File(this.repoDir, CHECKPOINT_FILE);

      Files.write(gitHash, tmpCheckpointFile, Charsets.UTF_8);

      Files.move(tmpCheckpointFile, checkpointFile);
    }

    void moveCheckpointAndHashesForward() throws IOException {
      this.lastProcessedGitHash = this.latestGitHash;
      if (this.shouldCheckpointHashes) {
        writeCheckpoint(this.latestGitHash);
      }
    }

    /**
     *
     * @throws GitAPIException
     * @throws IOException
     */
    List<DiffEntry> getChanges() throws GitAPIException, IOException {
      // get tree for last processed commit
      ObjectId oldHeadTree = git.getRepository().resolve(this.lastProcessedGitHash + "^{tree}");

      // refresh to latest and reset hard to handle forced pushes
      this.git.fetch()
          .setRemote(REMOTE_NAME)
          .setCredentialsProvider(getCredentialsProvider())
          .setTransportConfigCallback(buildTransportConfigCallback())
          .call();
      // reset hard to get a clean working set since pull --rebase may leave files around
      this.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(REMOTE_NAME + "/" + this.branchName).call();

      ObjectId head = this.git.getRepository().resolve("HEAD");
      ObjectId headTree = this.git.getRepository().resolve("HEAD^{tree}");

      // remember the hash for the current HEAD. This will be checkpointed after the diff is processed.
      latestGitHash = head.getName();

      // diff old and new heads to find changes
      ObjectReader reader = this.git.getRepository().newObjectReader();
      CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
      oldTreeIter.reset(reader, oldHeadTree);
      CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
      newTreeIter.reset(reader, headTree);

      return this.git.diff()
          .setNewTree(newTreeIter)
          .setOldTree(oldTreeIter)
          .setShowNameAndStatusOnly(true)
          .call();
    }

    private CredentialsProvider getCredentialsProvider() {
      return (this.providerSessionFactoryEither instanceof Either.Right)? null :
          ((Either.Left<CredentialsProvider, SshSessionFactory>) this.providerSessionFactoryEither).getLeft();
    }

    private TransportConfigCallback buildTransportConfigCallback() {
      if (this.providerSessionFactoryEither instanceof Either.Left) return null;

      SshSessionFactory sshSessionFactory = ((Either.Right<CredentialsProvider, SshSessionFactory>) this.providerSessionFactoryEither).getRight();
      return transport -> {
        SshTransport sshTransport = (SshTransport) transport;
        sshTransport.setSshSessionFactory(sshSessionFactory);
      };
    }
  }

  private SshSessionFactory getSshSessionFactory() {
    JschConfigSessionFactory sessionFactory = new JschConfigSessionFactory() {
      @Override
      protected void configure(OpenSshConfig.Host hc, Session session) {
        //Do nothing.
      }

      @Override
      protected JSch createDefaultJSch(FS fs) throws JSchException {
        if (GitMonitoringService.this.isJschLoggerEnabled) {
          JSch.setLogger(new JschLogger());
        }
        JSch defaultJSch = super.createDefaultJSch(fs);
        defaultJSch.addIdentity(GitMonitoringService.this.privateKeyPath, GitMonitoringService.this.passphrase);
        return defaultJSch;
      }
    };
    return sessionFactory;
  }

  private static class JschLogger implements com.jcraft.jsch.Logger {
    static Map<Integer, String> logMap = new HashMap<>();
    static {
      logMap.put(DEBUG, "DEBUG: ");
      logMap.put(INFO, "INFO: ");
      logMap.put(WARN, "WARN: ");
      logMap.put(ERROR, "ERROR: ");
      logMap.put(FATAL, "FATAL: ");
    }

    public boolean isEnabled(int level) {
      return true;
    }

    public void log(int level, String message) {
      System.err.print(logMap.get(level));
      System.err.println(message);
    }
  }

  public abstract boolean shouldPollGit();

  public abstract void addChange(DiffEntry change);

  public abstract void removeChange(DiffEntry change);
}
