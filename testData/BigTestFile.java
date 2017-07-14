package com.bronti.teamcity.mergeConflictCheckerPlugin;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;

/**
 * Created by bronti on 06.12.16.
 */

public class MergeConflictChecker {

    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {

        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {

                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {

    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {

        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();

                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }

    private File createTempLogFile() throws IOException {
        File logFile = new File(getBuildTempDirectory(), MergeConflictCheckerConstants.JSON_REPORT_FILENAME);
        logFile.createNewFile();
        return logFile;
    }

    private String createScript() throws RunBuildException {

        Map<String, String> params = getRunnerParameters();
        String myOption = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
//        MergeConflictCheckerMyOption rlOption = MergeConflictCheckerMyOption.valueOf(myOption);
        String allBranches = params.get(MergeConflictCheckerConstants.BRANCHES);

        BuildRunnerContext context = getRunnerContext();
        Map<String, String> configParams = context.getConfigParameters();
//        String user = configParams.get("vcsroot.username");
//        UsernamePasswordCredentialsProvider credentials =
//                new UsernamePasswordCredentialsProvider(user, "12345678");
        CredentialsProvider credentials =
                UsernamePasswordCredentialsProvider.getDefault();

        String currBranch = configParams.get("vcsroot.branch");
        String fetchUrl = configParams.get("vcsroot.url");

        try {
            URIish uri = new URIish(fetchUrl);

            File coDir = getCheckoutDirectory();
            File repoDir = new File(coDir.getPath() + "/.git");

            MergeConflictReportProvider logger;
            try {
                logger = new MergeConflictReportProvider(createTempLogFile(), artifactsWatcher);
            }
            catch (IOException ex) {
                throw new RunBuildException("Can not create temporary log file", ex.getCause());
            }

            MergeConflictChecker checker =
                    new MergeConflictChecker(repoDir, currBranch, allBranches, uri, credentials, logger);
            checker.check();
            return checker.getFeedback();
        }
        catch (URISyntaxException | IOException | GitAPIException ex) {
            throw new RunBuildException(ex.getMessage(), ex.getCause());
        }
    }

    private String createExecutable(String script) throws RunBuildException {
        File scriptFile;
        try {
            scriptFile = File.createTempFile("simple_build", null, getBuildTempDirectory());
            FileUtil.writeFileAndReportErrors(scriptFile, script);
        } catch (IOException e) {
            throw new RunBuildException("Cannot create a temp file for execution script.");
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RunBuildException("Cannot set executable permission to execution script file");
        }
        return scriptFile.getAbsolutePath();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunServiceFactory(@NotNull ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @Override
    public boolean canRun(BuildAgentConfiguration agentConfiguration) {
         
        return true;
    }

    @Override
    public CommandLineBuildService createService() {
        return new MergeConflictCheckerRunService(artifactsWatcher);
    }

    @Override
    public AgentBuildRunnerInfo getBuildRunnerInfo() {
        return this;
    }

    private class OneMergeResult {
        public final String branch;
        public final boolean isSuccessful;
        public final boolean exists;
        public final String state;

        OneMergeResult(String branch, boolean isSuccessful, String state) {
            this.branch = branch;
            this.isSuccessful = isSuccessful;
            this.exists = true;
            this.state = state;
        }

        OneMergeResult(String branch) {
            this.branch = branch;
            this.isSuccessful = false;
            this.exists = false;
            this.state = "";
        }
    }

    private List<OneMergeResult> results = new ArrayList<>();
    private File logFile;
    private ArtifactsWatcher artifactsWatcher;

    MergeConflictReportProvider(File logFile,
                                ArtifactsWatcher artifactsWatcher) {
        this.logFile = logFile;
        this.artifactsWatcher = artifactsWatcher;
    }

    void logMergeResult(String branch, boolean isSuccessful, String state)
    {
        results.add(new OneMergeResult(branch, isSuccessful, state));
    }

    void logNonexistentBranch(String branch)
    {
        results.add(new OneMergeResult(branch));
    }

    void flushLog() throws IOException {
        JsonFactory jf = new MappingJsonFactory();
        try (JsonGenerator jg = jf.createGenerator(logFile, JsonEncoding.UTF8)) {
            jg.writeStartObject();
            jg.writeFieldName("merge_results");
            jg.writeObject(results);
            jg.writeEndObject();
        }
        artifactsWatcher.addNewArtifactsPath(logFile.getAbsolutePath() + "=>" + MergeConflictCheckerConstants.ARTIFACTS_DIR);
    }



    public static final String RUN_TYPE = "merge_conflict_checker";

    public static final String MY_OPTION_KEY = "my_option_key";

    public static final String BRANCHES = "branches";

    public static final String ARTIFACTS_DIR = ".teamcity/mccr-report";

    public static final String JSON_REPORT_FILENAME = "mccr-report.json";

    public String getValue() {
        return this.name();
    }

    public final String branch;
    public final boolean isSuccessful;
    public final boolean exists;
    public final String state;

    OneMergeResult(String branch, boolean isSuccessful, String state) {
        this.branch = branch;
        this.isSuccessful = isSuccessful;
        this.exists = true;
        this.state = state;
    }

    OneMergeResult(String branch) {
        this.branch = branch;
        this.isSuccessful = false;
        this.exists = false;
        this.state = "";
    }


    public MergeConflictCheckerReportTab(@NotNull PagePlaces pagePlaces,
                                         @NotNull SBuildServer server,
                                         @NotNull PluginDescriptor descriptor) {
        super("", "", pagePlaces, server);
        setTabTitle(getTitle());
        setPluginName(getClass().getSimpleName());
        setIncludeUrl(descriptor.getPluginResourcesPath("buildResultsTab.jsp"));
        addCssFile(descriptor.getPluginResourcesPath("css/style.css"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular.min.js"));
        addJsFile(descriptor.getPluginResourcesPath("js/angular-app.js"));
    }

    private String getTitle() {
        return "Merge Conflict Checker Report";
    }

    @Override
    protected void fillModel(@NotNull Map<String, Object> model,
                             @NotNull HttpServletRequest request,
                             @NotNull SBuild build) {
    }

    @Override
    protected boolean isAvailable(@NotNull final HttpServletRequest request, @NotNull final SBuild build) {
        return build.getBuildType().getRunnerTypes().contains(MergeConflictCheckerConstants.RUN_TYPE);
    }


    public String getMyOption() {
        return MergeConflictCheckerConstants.MY_OPTION_KEY;
    }

    public String getBranches() {
        return MergeConflictCheckerConstants.BRANCHES;
    }

    public Collection<MergeConflictCheckerMyOption> getMyOptionValues() {
        return Arrays.asList(MergeConflictCheckerMyOption.values());
    }

    public String getFirstMyValue() {
        return MergeConflictCheckerMyOption.FIRST.getValue();
    }

    public String getSecondMyValue() {
        return MergeConflictCheckerMyOption.SECOND.getValue();
    }


    private PluginDescriptor pluginDescriptor;

    public MergeConflictCheckerRunTYpe(@NotNull final RunTypeRegistry reg,
                                       @NotNull final PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
        reg.registerRunType(this);
    }

    @NotNull
    @Override
    public String getType() {
        return MergeConflictCheckerConstants.RUN_TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Merge Conflict Checker";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Checks for merge conflicts.";
    }

    @Override
    public String getEditRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("editMergeConflictCheckerRun.jsp");
    }

    @Override
    public String getViewRunnerParamsJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath("viewMergeConflictCheckerRun.jsp");
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultRunnerProperties() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(MergeConflictCheckerConstants.MY_OPTION_KEY, MergeConflictCheckerMyOption.SECOND.getValue());
//        defaults.put(MergeConflictCheckerConstants.BRANCHES, "");
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getRunnerPropertiesProcessor() {
        return new PropertiesProcessor() {
            public Collection<InvalidProperty> process(final Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<InvalidProperty>();                
                return errors;
            }
        };
    }

    @NotNull
    @Override
    public String describeParameters(@NotNull Map<String, String> params) {
        String value = params.get(MergeConflictCheckerConstants.MY_OPTION_KEY);
        String result = value == null ? "something went wrong (my option is null)\n" : "my option: " + value + "\n";
        String branches = params.get(MergeConflictCheckerConstants.BRANCHES);
        result += "branches: " + (branches == null ? "null" : branches) + "\n";
        return result;
    }


    //    MergeConflictCheckerRunService runner;
    private StringBuilder script = new StringBuilder(100);

    private final CredentialsProvider creds;
    private final String[] toCheckBranches;
    private final String currentBranch;
    private final URIish fetchUri;
    private final String originName = "origin";
    private final MergeConflictReportProvider logger;

    private Repository repository;
    private Git git;

    MergeConflictChecker(File repoDir,
                         String branch,
                         String branches,
                         URIish uri,
                         CredentialsProvider creds,
                         MergeConflictReportProvider logger) throws IOException {
        script.append("#!/bin/bash\n\n");
        this.creds = creds;
        
        toCheckBranches = branches.split("\\s+");
        fetchUri = uri;
        currentBranch = branch;

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().findGitDir().build();
        git = new Git(repository);
        this.logger = logger;
    }

    private enum TcMessageStatus {
        NORMAL, WARNING, FAILURE, ERROR
    }

    private void tcMessage(String msg, TcMessageStatus status, String details)
    {
        script.append("echo \"##teamcity[message text='");
        script.append(msg);
        if (!details.equals("") && status == TcMessageStatus.ERROR)
        {
            script.append("'  errorDetails='");
            script.append(details);
        }
        script.append("' status='");
        script.append(status.name());
        script.append("']\"\n");
    }

    private void tcMessage(String msg, TcMessageStatus status)
    {
        tcMessage(msg, status, "");
    }

    String getFeedback()
    {
        return script.toString();
    }

    private void fetchRemote(Git git) throws GitAPIException {
        RemoteAddCommand addOrigin = git.remoteAdd();
        addOrigin.setName(originName);
        addOrigin.setUri(fetchUri);
        addOrigin.call();

        // one remote
        RefSpec refSpec = new RefSpec();
        refSpec = refSpec.setForceUpdate(true);
        // all branches
        refSpec = refSpec.setSourceDestination("refs/heads/*", "refs/remotes/" + originName + "/*");

        git.fetch()
                .setRemote(originName)
                .setCredentialsProvider(creds)
                .setRefSpecs(refSpec)
                .call();

         
        tcMessage("Successfully fetched " + originName, TcMessageStatus.NORMAL);
    }

    void check() throws GitAPIException, IOException {

        fetchRemote(git);

        List<Ref> brchs = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call();
        for (Ref br : brchs) {
            echo(br.getName());
        }

        for (String branch : toCheckBranches) {
            MergeCommand mgCmd = git.merge();
            ObjectId commitId = repository.resolve("refs/remotes/" + originName + "/" + branch);
            if (commitId == null) {
                 
                tcMessage("Branch |'" + branch + "|' not found", TcMessageStatus.ERROR);
                logger.logNonexistentBranch(branch);
                continue;
            }
            mgCmd.include(commitId);
            MergeResult.MergeStatus resStatus = mgCmd.call().getMergeStatus();
            if (resStatus.isSuccessful()) {
                 
                String msg = "Merge with branch |'" + branch + "|' is successful. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.NORMAL);
            } else {
                 
                String msg = "Merge with branch |'" + branch + "|' is failed. Status is " + resStatus.toString() + ".";
                tcMessage(msg, TcMessageStatus.WARNING);
            }
            logger.logMergeResult(branch, resStatus.isSuccessful(), resStatus.toString());

            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);

            Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        logger.flushLog();
    }


    private ArtifactsWatcher artifactsWatcher;

    public MergeConflictCheckerRunService(ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    @Override
    public void beforeProcessStarted() throws RunBuildException {
         
    }

    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {
        String workingDir = getWorkingDirectory().getPath();
        String script = createScript();
        List<String> args = Collections.emptyList();

        return new SimpleProgramCommandLine(
                getEnvironmentVariables(),
                workingDir,
                createExecutable(script),
                args);
    }


    public class ValgrindConsoleView implements ConsoleView {

        private static final String DEFAULT_ERRORS_TEXT = "Nothing to show yet.\n";
        private static final String ERROR_ERRORS_TEXT = "error\n";

        private @NotNull final JBSplitter mainPanel;
        private @NotNull final Project project;
        private @NotNull final ConsoleView console;
        private @NotNull Editor errorsEditor;
        private @NotNull final String pathToXml;
        private @NotNull final RegexpFilter fileRefsFilter;

        private EditorHyperlinkSupport hyperlinks;

//    private static final int CONSOLE_COLUMN_MIN_WIDTH = 300;
//    private static final int ERRORS_COLUMN_MIN_WIDTH  = 300;

        public ValgrindConsoleView(@NotNull final Project project, @NotNull ConsoleView console, @NotNull String pathToXml) {
            this.project = project;
            this.console = console;
            this.pathToXml = pathToXml;
            mainPanel = new JBSplitter();
            JComponent consoleComponent = console.getComponent();
            mainPanel.setFirstComponent(consoleComponent);

            EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());

            fileRefsFilter = new RegexpFilter(project, "$FILE_PATH$:$LINE$");
            errorsEditor = editorFactory.createViewer(editorFactory.createDocument(DEFAULT_ERRORS_TEXT), project);
            hyperlinks = new EditorHyperlinkSupport(errorsEditor, project);
             
            hyperlinks.highlightHyperlinks(fileRefsFilter, 0,1);

            mainPanel.setSecondComponent(errorsEditor.getComponent());

//        JTree tree = new Tree(errors.getTree());
//        tree.add(new JScrollBar(Adjustable.HORIZONTAL));
//        tree.add("hello", new JLabel("world"));
//        String tmp = errors.toString();
//        EditorFactory editorFactory = new EditorFactoryImpl(EditorActionManager.getInstance());
//        Editor errorsEditor = editorFactory.createViewer(editorFactory.createDocument(tmp), project);
//        mainPanel.setSecondComponent(tree);
//        mainPanel.setSecondComponent(errorsEditor.getComponent());
        }

        public void refreshErrors() {
            String allErrors;
            int linesCount = 1;
            try {
                ErrorsHolder errors = Parser.parse(pathToXml);
//            allErrors = "/home/bronti/all/au/devDays/test/cpptest/main.cpp:5\n\n\n";
                allErrors = errors.toString();
                linesCount = allErrors.split("\r\n|\r|\n").length - 1;
            }
            catch (Exception ex) {
                allErrors = DEFAULT_ERRORS_TEXT;
            }
            final String finalText = allErrors;
            final int finalLinesCount = linesCount;

            hyperlinks.clearHyperlinks();
            ApplicationManager.getApplication().invokeLater(()-> {
                ApplicationManager.getApplication().runWriteAction(() ->{
                    errorsEditor.getDocument().setText(finalText);
                     
                    hyperlinks.highlightHyperlinks(fileRefsFilter, 0, finalLinesCount);
//                mainPanel.setSecondComponent(errorsEditor.getComponent());
                });
            });
        }

        @Override
        public JComponent getComponent() {
            return mainPanel;
        }

        @Override
        public void dispose() {
            hyperlinks = null;
        }

        @Override
        public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {}

        @Override
        public void clear() {}

        @Override
        public void scrollTo(int offset) {}

        @Override
        public void attachToProcess(ProcessHandler processHandler) { console.attachToProcess(processHandler); }

        @Override
        public void setOutputPaused(boolean value) {}

        @Override
        public boolean isOutputPaused() {
            return false;
        }

        @Override
        public boolean hasDeferredOutput() {
            return false;
        }

        @Override
        public void performWhenNoDeferredOutput(@NotNull Runnable runnable) {}

        @Override
        public void setHelpId(@NotNull String helpId) {}

        @Override
        public void addMessageFilter(@NotNull Filter filter) { console.addMessageFilter(filter); }

        @Override
        public void printHyperlink(@NotNull String hyperlinkText, HyperlinkInfo info) {}

        @Override
        public int getContentSize() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return false;
        }

        @NotNull
        @Override
        public AnAction[] createConsoleActions() {
            return AnAction.EMPTY_ARRAY;
        }

        @Override
        public void allowHeavyFilters() {}

        @Override
        public JComponent getPreferredFocusableComponent() {
            return mainPanel.getSecondComponent();
        }
    }


    public class ValgrindRunConsoleBuilder extends TextConsoleBuilder {
        private final Project project;
        private final ArrayList<Filter> myFilters = Lists.newArrayList();
        private String pathToXml;
        private ProcessHandler process;

        public ValgrindRunConsoleBuilder(final Project project, ProcessHandler process, String pathToXml) {
            this.project = project;
            this.pathToXml = pathToXml;
            this.process = process;
        }

        @Override
        public ConsoleView getConsole() {
            final ConsoleView consoleView = createConsole();
            for (final Filter filter : myFilters) {
                consoleView.addMessageFilter(filter);
            }
            return consoleView;
        }

        protected ConsoleView createConsole() {
            ConsoleView outputConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            outputConsole.attachToProcess(process);

            ValgrindConsoleView resultConsole = new ValgrindConsoleView(project, outputConsole, pathToXml);
            process.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(ProcessEvent event) {
                    resultConsole.refreshErrors();
                }
            });
            return resultConsole;
        }

        @Override
        public void addFilter(@NotNull final Filter filter) {
            myFilters.add(filter);
        }

        @Override
        public void setViewer(boolean isViewer) {
        }
    }


    public class ValgrindCommandLineState extends CommandLineState {

        private GeneralCommandLine commandLine;

         
        private String pathToXml;

        public ValgrindCommandLineState(ExecutionEnvironment executionEnvironment, String pathToXml, GeneralCommandLine commandLine)
        {
            super(executionEnvironment);
            this.commandLine = commandLine;
            this.pathToXml = pathToXml;
        }

        @NotNull
        @Override
        protected ProcessHandler startProcess() throws ExecutionException {
            Project project = getEnvironment().getProject();

            ColoredProcessHandler process = new ColoredProcessHandler(commandLine);

            setConsoleBuilder(new ValgrindRunConsoleBuilder(project, process, pathToXml));
            ProcessTerminatedListener.attach(process, project);
            return process;
        }
    }


    public class ValgrindConfigurationFactory extends ConfigurationFactory {
        private static final String FACTORY_NAME = "Valgrind configuration factory";

        protected ValgrindConfigurationFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new ValgrindRunConfiguration(project, this, "Valgrind");
        }

        @Override
        public String getName() {
            return FACTORY_NAME;
        }
    }


    public class ValgrindRunConfiguration extends RunConfigurationBase {
        Project myProject;
        protected ValgrindRunConfiguration(Project project, ConfigurationFactory factory, String name) {
            super(project, factory, name);
            myProject = project;
        }

        @NotNull
        @Override
        public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
            return new ValgrindSettingsEditor();
        }

        @Override
        public void checkConfiguration() throws RuntimeConfigurationException {
        }

        private String getBuildDir() {
            CMakeWorkspace cMakeWorkspace = CMakeWorkspace.getInstance(myProject);

            List<CMakeSettings.Configuration> configurations =
                    cMakeWorkspace.getSettings().getConfigurations();
            if (configurations.isEmpty()) {
                throw new RuntimeException();
            }

            // select the first configuration in the list
            // cannot get active configuration for the current project.
            // code from https://intellij-support.jetbrains.com
            // /hc/en-us/community/posts/115000107544-CLion-Get-cmake-output-directory
            // doesn't work
            CMakeSettings.Configuration selectedConfiguration = configurations.get(0);
            String selectedConfigurationName = selectedConfiguration.getConfigName();

            // get the path of generated files of the selected configuration
            List<File> buildDir = cMakeWorkspace.getEffectiveConfigurationGenerationDirs(
                    Arrays.asList(Pair.create(selectedConfigurationName, null)));
            return buildDir.get(0).getAbsolutePath();

        }

        @Nullable
        @Override
        public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) throws ExecutionException {

            String executable = getBuildDir() + "/"
                    + executionEnvironment.getProject().getName();
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", executable)
                    .withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return createCommandLineState(executionEnvironment, cl);
        }

        private RunProfileState createCommandLineState(@NotNull ExecutionEnvironment executionEnvironment,
                                                       GeneralCommandLine commandLine) {
             
             
            String pathToExecutable = getBuildDir() + "/" + executionEnvironment.getProject().getName();
            String pathToXml = getBuildDir() + "/" + executionEnvironment.getProject().getName() + "-valgrind-results.xml";
            GeneralCommandLine cl = new GeneralCommandLine("valgrind", "--leak-check=full",
                    "--xml=yes", "--xml-file=" + pathToXml, pathToExecutable);
            cl = cl.withWorkDirectory(executionEnvironment.getProject().getBasePath());
            return new ValgrindCommandLineState(executionEnvironment, pathToXml, cl);
        }
    }

}
