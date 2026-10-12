package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ApplicationDefinition;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.DeploymentDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.config.SecuritySettings;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.ConfigValue;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des produktiven {@link LocalGitConfigurationReader} gegen ein temporaeres, echtes
 * lokales Git-Repository: richtige Revision, keine Worktree-Aenderung, Allowlist, Symlinks,
 * Hooks, Budget und Herkunft der Werte.
 */
class LocalGitConfigurationReaderTest {

    private static final String REPOSITORY_KEY = "deployment-config";
    private static final String REPOSITORY_ID = "sogis/openshift-templates";
    private static final List<String> ALLOWED_PARAMETERS =
            List.of("IMAGE_REF", "MIN_READY_SECONDS", "HEALTH_PATH", "PROGRESS_DEADLINE");

    @TempDir
    Path tempDir;

    private TempGitRepo repo;
    private RecordingProcessRunner runner;
    private Clock clock;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        Assumptions.assumeTrue(TempGitRepo.gitAvailable(), "git ist im PATH nicht verfuegbar");
        repo = TempGitRepo.create(tempDir);
        Assumptions.assumeTrue(
                Files.isExecutable(repo.root().resolve(".git/hooks/post-checkout")),
                "Hooks sind auf diesem Dateisystem nicht ausfuehrbar");
        Assumptions.assumeTrue(repo.proveHookIsLive(),
                "Der post-checkout-Hook feuert nicht; die Markerpruefung waere wirkungslos");
        runner = RecordingProcessRunner.withHostileEnvironment(tempDir);
        clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void readsCommitAWithoutChangingHeadOrWorktree() throws Exception {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        Map<String, String> hashesBefore = repo.worktreeHashes();
        String headBefore = repo.head();
        assertEquals(repo.commitB(), headBefore);

        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        assertEquals(List.of(), result.errors());
        ResolvedConfiguration data = result.data();
        assertEquals(ResolvedConfiguration.ConfigStatus.RESOLVED, data.status());
        assertEquals(REPOSITORY_ID, data.repositoryId());
        assertEquals(repo.commitA(), data.revision().commit());
        assertEquals(List.of("main", "sidecar"), data.containerNames());
        assertEquals("operator_supplied_unverified", data.revision().provenanceLevel());

        // Werte aus Commit A, nicht aus HEAD (B).
        ProbeConfig liveness = data.workloads().get("main").probes().get("liveness");
        assertNotNull(liveness);
        assertEquals(TempGitRepo.A_INITIAL_DELAY, liveness.initialDelaySeconds());
        assertEquals(TempGitRepo.A_FAILURE_THRESHOLD, liveness.failureThreshold());
        assertNotEquals(TempGitRepo.B_INITIAL_DELAY, liveness.initialDelaySeconds());
        assertEquals("600", data.rolloutTimeParameters().get("progressDeadlineSeconds").value());
        assertEquals("registry.example.invalid/fixture-app:1.0.0", data.workloads().get("main").image());

        for (FileReadResult file : data.files()) {
            assertEquals(FileReadResult.FileStatus.READ, file.status(), file.note());
            assertEquals(repo.commitA(), file.commit());
            assertNotNull(file.sha256());
            assertFalse(file.lines().isEmpty());
        }

        // Weder HEAD noch Arbeitsbaum wurden veraendert.
        assertEquals(headBefore, repo.head());
        assertEquals(repo.commitB(), repo.head());
        assertEquals(hashesBefore, repo.worktreeHashes());
        assertEquals(List.of(), repo.markers(), "Hooks/externe Programme duerfen nicht laufen");

        // Nur lesende Aufrufe mit fester Haertung.
        assertFalse(runner.calls().isEmpty());
        for (FakeProcessRunner.Call call : runner.calls()) {
            assertEquals("git", call.executable());
            assertTrue(GitProcess.READ_ONLY_COMMANDS.contains(call.subcommand()), call.joined());
            assertEquals("-C", call.arguments().get(0));
            assertEquals(List.of("-c", call.arguments().get(3),
                    "-c", "diff.external=",
                    "-c", "core.pager=cat"), call.arguments().subList(2, 8));
            assertEquals(repo.root().toAbsolutePath().normalize().toString(), call.arguments().get(1));
        }
    }

    @Test
    void hookAndEnvironmentHardeningHoldAgainstHostileParentEnvironment() throws IOException {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        reader.collect(target, revision(repo.commitA()), target.budget());

        assertEquals(List.of(), repo.markers(), "hostile pager/diff/hook duerfen nicht ausgefuehrt werden");
        Map<String, String> env = runner.calls().get(0).environment();
        assertEquals("cat", env.get("GIT_PAGER"));
        assertEquals("", env.get("GIT_EXTERNAL_DIFF"));
        assertNotEquals(tempDir.toString(), env.get("HOME"));
        assertEquals("1", env.get("GIT_CONFIG_NOSYSTEM"));
        assertEquals("/bin/false", env.get("GIT_ASKPASS"));
        assertTrue(env.get("GIT_CONFIG_GLOBAL").endsWith("gitconfig"));
        assertTrue(Files.isRegularFile(Path.of(env.get("GIT_CONFIG_GLOBAL"))));
        for (FakeProcessRunner.Call call : runner.calls()) {
            assertTrue(call.joined().contains("-c core.hooksPath="), call.joined());
            assertTrue(call.joined().contains("-c diff.external= -c core.pager=cat"), call.joined());
        }
    }

    @Test
    void missingCommitIsUnavailableWithoutFallback() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        String missing = "0".repeat(40);
        ResolvedTarget target = target(config, missing);
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(missing), target.budget());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ResolvedConfiguration.ConfigStatus.UNAVAILABLE, result.data().status());
        assertTrue(result.data().containerNames().isEmpty());
        assertTrue(hasCode(result.errors(), ErrorCode.REVISION_UNAVAILABLE), result.errors().toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Kein Ersatz")), result.notes().toString());

        // Kein show/ls-tree: es wurde keine Datei gelesen und kein Ersatzcommit verwendet.
        assertEquals(List.of("cat-file"), runner.subcommands());
        assertFalse(runner.gitArgumentLists().stream().anyMatch(arguments -> arguments.contains("show")));
    }

    @Test
    void foreignRepositoryRevisionIsNotAssigned() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        RevisionProvenance foreign = new RevisionProvenance("some/other-repository", repo.commitB(),
                repo.commitB().substring(0, 8), RevisionProvenance.Source.OPERATOR_SUPPLIED, 42, null,
                "SHA aus einem anderen Repository");
        ResolvedTarget target = target(config, repo.commitB());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, foreign, target.budget());

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ResolvedConfiguration.ConfigStatus.UNAVAILABLE, result.data().status());
        assertTrue(hasCode(result.errors(), ErrorCode.SCOPE_DENIED), result.errors().toString());
        assertTrue(hasCode(result.errors(), ErrorCode.REVISION_UNVERIFIED), result.errors().toString());
        assertEquals(List.of(), runner.calls(), "es darf kein Git-Aufruf entstehen");
    }

    @Test
    void missingRevisionReadsNothing() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target,
                RevisionProvenance.unknown(REPOSITORY_ID, "keine Zuordnung"), target.budget());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ResolvedConfiguration.ConfigStatus.UNAVAILABLE, result.data().status());
        assertTrue(hasCode(result.errors(), ErrorCode.REVISION_UNKNOWN), result.errors().toString());
        assertTrue(result.data().files().isEmpty());
        assertEquals(List.of(), runner.calls());

        // Jenkins-SCM ohne Treffer: die Zuordnung ist nicht belegt, es wird nichts gelesen.
        RevisionProvenance unverified = new RevisionProvenance(REPOSITORY_ID, null, null,
                RevisionProvenance.Source.JENKINS_SCM, 42, "EV-0001", "Kein SCM-Treffer");
        SourceResult<ResolvedConfiguration> second = reader.collect(target, unverified, target.budget());
        assertEquals(SourceStatus.NOT_PRESENT, second.status());
        assertTrue(hasCode(second.errors(), ErrorCode.REVISION_UNVERIFIED), second.errors().toString());
        assertEquals(List.of(), runner.calls());
    }

    @Test
    void invalidCommitIdentifierIsRejectedWithoutGitCall() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        RevisionProvenance fromModel = new RevisionProvenance(REPOSITORY_ID, "main", "main",
                RevisionProvenance.Source.OPERATOR_SUPPLIED, 42, null, "Ref statt Commit");
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, fromModel, target.budget());

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertTrue(hasCode(result.errors(), ErrorCode.SCOPE_DENIED), result.errors().toString());
        assertTrue(hasCode(result.errors(), ErrorCode.REVISION_UNVERIFIED), result.errors().toString());
        assertEquals(List.of(), runner.calls());
    }

    @Test
    void unallowedFilesAreNotRead() {
        OpsConfig config = config(repo.allowedFiles(),
                List.of(TempGitRepo.DEPLOYMENT, TempGitRepo.UNALLOWED), List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        FileReadResult unallowed = fileOf(result.data(), TempGitRepo.UNALLOWED);
        assertEquals(FileReadResult.FileStatus.NOT_ALLOWED, unallowed.status());
        assertTrue(unallowed.lines().isEmpty());
        assertEquals(ResolvedConfiguration.ConfigStatus.PARTIAL, result.data().status());
        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(hasCode(result.errors(), ErrorCode.SCOPE_DENIED), result.errors().toString());
        assertTrue(runner.calls().stream().noneMatch(call -> call.joined().contains("unallowed")),
                "fuer eine nicht erlaubte Datei darf kein Git-Aufruf entstehen");
        assertTrue(result.data().containerNames().contains("main"));
    }

    @Test
    void symlinkEscapeIsNotRead() {
        OpsConfig config = config(repo.allowedFiles(),
                List.of(TempGitRepo.DEPLOYMENT, TempGitRepo.ESCAPE), List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        FileReadResult escape = fileOf(result.data(), TempGitRepo.ESCAPE);
        assertEquals(FileReadResult.FileStatus.NOT_ALLOWED, escape.status());
        assertTrue(escape.note().contains("Symlink"), escape.note());
        assertEquals(ResolvedConfiguration.ConfigStatus.PARTIAL, result.data().status());
        assertTrue(hasCode(result.errors(), ErrorCode.SCOPE_DENIED), result.errors().toString());
        assertTrue(runner.calls().stream().noneMatch(
                call -> "show".equals(call.subcommand()) && call.joined().contains(TempGitRepo.ESCAPE)),
                "der Symlink darf nicht ueber git show gelesen werden");
        assertFalse(allText(result.data()).contains("fixture-outside-canary-0123456789"));
    }

    @Test
    void secretDocumentsAreNotDelivered() {
        OpsConfig config = config(repo.allowedFiles(),
                List.of(TempGitRepo.DEPLOYMENT, TempGitRepo.SECRET), List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        FileReadResult secret = fileOf(result.data(), TempGitRepo.SECRET);
        assertEquals(FileReadResult.FileStatus.UNSUPPORTED_TYPE, secret.status());
        assertTrue(secret.note().contains("Secret"), secret.note());
        assertTrue(hasCode(result.errors(), ErrorCode.UNSUPPORTED_WORKLOAD), result.errors().toString());
        assertFalse(allText(result.data()).contains("fixture-secret-canary-0123456789"));
        assertTrue(secret.lines().stream().allMatch(line -> line.contains("nicht uebernommen")),
                secret.lines().toString());
        assertFalse(allText(result.data()).contains("fixture-env-canary-0123456789"));
        assertFalse(allText(result.data()).contains("DB_PASSWORD="));
    }

    @Test
    void maxGitFilesIsRespected() {
        OpsConfig config = config(repo.allowedFiles(),
                List.of(TempGitRepo.DEPLOYMENT, TempGitRepo.SECRET), List.of(TempGitRepo.PARAMETERS), 2);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        long reads = runner.gitArgumentLists().stream().filter(arguments -> "show".equals(arguments.get(0))).count();
        assertEquals(2, reads, "maxGitFiles=2 darf nicht ueberschritten werden");
        assertTrue(result.data().files().stream().noneMatch(file -> file.relativePath().equals(TempGitRepo.PARAMETERS)),
                "die nicht gelesene Datei darf nicht als gelesen erscheinen");
        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(hasCode(result.errors(), ErrorCode.BUDGET_EXCEEDED), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains(TempGitRepo.PARAMETERS)),
                result.errors().toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("maxGitFiles")), result.notes().toString());
    }

    @Test
    void configValuesCarryFileAndLine() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());

        ConfigValue deadline = result.data().rolloutTimeParameters().get("progressDeadlineSeconds");
        assertNotNull(deadline);
        assertEquals(TempGitRepo.DEPLOYMENT, deadline.templateFile());
        assertEquals(6, deadline.templateLine());
        assertEquals(String.valueOf(TempGitRepo.A_PROGRESS_DEADLINE), deadline.value());

        ConfigValue minReady = result.data().rolloutTimeParameters().get("minReadySeconds");
        assertNotNull(minReady);
        assertEquals(ConfigValue.Resolution.SUBSTITUTED, minReady.resolution());
        assertEquals(TempGitRepo.PARAMETERS, minReady.parameterFile());
        assertEquals(3, minReady.parameterLine());
        assertEquals("MIN_READY_SECONDS", minReady.parameterName());
        assertTrue(minReady.provenance().contains(TempGitRepo.PARAMETERS + ":3"));
        assertEquals("5", minReady.value());
    }

    @Test
    void headCommitIsDiagnosticOnly() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        assertEquals(repo.commitB(), reader.headCommit());

        ResolvedTarget target = target(config, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());
        assertEquals(repo.commitA(), result.data().revision().commit());
        assertEquals(repo.commitB(), reader.headCommit(), "HEAD bleibt unveraendert");
    }

    @Test
    void unconfiguredRepositoriesAndMissingCheckoutsYieldExplicitGaps() {
        OpsConfig withoutPath = config(Map.of(REPOSITORY_KEY,
                new RepositoryDefinition(REPOSITORY_KEY, REPOSITORY_ID, null, List.of(TempGitRepo.DEPLOYMENT))),
                List.of(TempGitRepo.DEPLOYMENT), List.of(), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(withoutPath, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(withoutPath, repo.commitA());
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), target.budget());
        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertTrue(hasCode(result.errors(), ErrorCode.CONFIG_INCOMPLETE), result.errors().toString());
        assertEquals(List.of(), runner.calls());

        Path notARepository = tempDir.resolve("outside");
        OpsConfig wrongPath = config(Map.of(REPOSITORY_KEY,
                new RepositoryDefinition(REPOSITORY_KEY, REPOSITORY_ID, notARepository,
                        List.of(TempGitRepo.DEPLOYMENT))),
                List.of(TempGitRepo.DEPLOYMENT), List.of(), 12);
        LocalGitConfigurationReader wrongReader = new LocalGitConfigurationReader(wrongPath, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget wrongTarget = target(wrongPath, repo.commitA());
        SourceResult<ResolvedConfiguration> wrongResult = wrongReader.collect(wrongTarget, revision(repo.commitA()),
                wrongTarget.budget());
        assertEquals(SourceStatus.UNREACHABLE, wrongResult.status());
        assertTrue(hasCode(wrongResult.errors(), ErrorCode.CONFIG_INVALID), wrongResult.errors().toString());
        assertEquals(List.of(), runner.calls());
    }

    @Test
    void exhaustedBudgetStopsBeforeReading() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitA());
        CollectionBudget noTime = new CollectionBudget(Duration.ZERO, clock.instant(), 60, 8, 500,
                32L * 1024 * 1024, 32 * 1024);
        SourceResult<ResolvedConfiguration> result = reader.collect(target, revision(repo.commitA()), noTime);

        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(hasCode(result.errors(), ErrorCode.BUDGET_EXCEEDED), result.errors().toString());
        assertEquals(List.of(), runner.calls());

        CollectionBudget noRequests = new CollectionBudget(Duration.ofSeconds(60), clock.instant(), 0, 8, 500,
                32L * 1024 * 1024, 32 * 1024);
        SourceResult<ResolvedConfiguration> second = reader.collect(target, revision(repo.commitA()), noRequests);
        assertEquals(SourceStatus.TRUNCATED, second.status());
        assertTrue(hasCode(second.errors(), ErrorCode.BUDGET_EXCEEDED), second.errors().toString());
        assertEquals(List.of(), runner.calls());
    }

    @Test
    void sourceIdComesFromConfiguration() {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        assertEquals(REPOSITORY_KEY, reader.sourceId());

        LocalGitConfigurationReader bound = new LocalGitConfigurationReader(config, REPOSITORY_KEY, runner, clock,
                YamlSupport.jsonMapper());
        assertEquals(REPOSITORY_KEY, bound.sourceId());
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private OpsConfig config(List<String> allowedFiles, List<String> templateFiles, List<String> parameterFiles,
            int maxGitFiles) {
        RepositoryDefinition repository = new RepositoryDefinition(REPOSITORY_KEY, REPOSITORY_ID, repo.root(),
                allowedFiles);
        return config(Map.of(REPOSITORY_KEY, repository), templateFiles, parameterFiles, maxGitFiles);
    }

    private OpsConfig config(Map<String, RepositoryDefinition> repositories, List<String> templateFiles,
            List<String> parameterFiles, int maxGitFiles) {
        ConfigurationDefinition configuration = new ConfigurationDefinition(REPOSITORY_KEY, templateFiles,
                parameterFiles, ALLOWED_PARAMETERS,
                new ConfigurationDefinition.RevisionDefinition(
                        ConfigurationDefinition.RevisionDefinition.Source.OPERATOR_SUPPLIED, REPOSITORY_ID,
                        repo.commitB(), 42));
        EnvironmentDefinition environment = new EnvironmentDefinition("ocp", "fixture-integration",
                new WorkloadDefinition(WorkloadDefinition.WorkloadKind.DEPLOYMENT, "fixture-app", List.of(), false),
                new DeploymentDefinition("apps-deploy", List.of("folder", "fixture-app")), configuration);
        ApplicationDefinition application = new ApplicationDefinition("fixture-app", "Fixture App",
                Map.of("integration", environment));
        Limits limits = new Limits(90, 15, 5, 3, 60, 8, 500, maxGitFiles, 8 * 1024 * 1024, 1024 * 1024,
                32 * 1024 * 1024, 32 * 1024, 256 * 1024);
        return new OpsConfig(1, DataMode.LIVE, tempDir.resolve("config.yaml"), tempDir.resolve("state"),
                new SecuritySettings(false, SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY,
                        SecuritySettings.IDENTITY_RESTRICTED),
                limits, Map.of(), Map.of(), repositories, Map.of("fixture-app", application), null);
    }

    private ResolvedTarget target(OpsConfig config, String commit) {
        EnvironmentDefinition definition = config.application("fixture-app").environment("integration");
        CollectionBudget budget = new CollectionBudget(Duration.ofSeconds(60), clock.instant(), 60, 8, 500,
                32L * 1024 * 1024, 32 * 1024);
        return new ResolvedTarget("TARGET-1", config, DataMode.LIVE, "fixture-app", "integration", definition,
                new BuildRef("apps-deploy", List.of("folder", "fixture-app"), 42), null, clock.instant(),
                clock.instant().plusSeconds(1800), budget, List.of(), null);
    }

    private RevisionProvenance revision(String commit) {
        return new RevisionProvenance(REPOSITORY_ID, commit,
                commit == null ? null : commit.substring(0, 8),
                RevisionProvenance.Source.OPERATOR_SUPPLIED, 42, null, "Testfixture");
    }

    private static FileReadResult fileOf(ResolvedConfiguration configuration, String path) {
        return configuration.files().stream()
                .filter(file -> file.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Datei fehlt im Ergebnis: " + path));
    }

    private static boolean hasCode(List<OpsError> errors, ErrorCode code) {
        return errors.stream().anyMatch(error -> error.code() == code);
    }

    private static String allText(ResolvedConfiguration configuration) {
        StringBuilder sb = new StringBuilder();
        configuration.files().forEach(file -> file.lines().forEach(line -> sb.append(line).append('\n')));
        configuration.workloads().values().forEach(container -> sb.append(container).append('\n'));
        configuration.rolloutTimeParameters().forEach((name, value) -> sb.append(value.toMap()).append('\n'));
        return sb.toString();
    }

    @Test
    void readingDoesNotCreateAnyMarkerFile() throws IOException {
        OpsConfig config = config(repo.allowedFiles(), List.of(TempGitRepo.DEPLOYMENT),
                List.of(TempGitRepo.PARAMETERS), 12);
        LocalGitConfigurationReader reader = new LocalGitConfigurationReader(config, runner, clock,
                YamlSupport.jsonMapper());
        ResolvedTarget target = target(config, repo.commitB());
        reader.collect(target, revision(repo.commitB()), target.budget());
        assertEquals(List.of(), repo.markers());
    }
}
