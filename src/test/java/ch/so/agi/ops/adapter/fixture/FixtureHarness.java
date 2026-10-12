package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.application.OwnershipGraph;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ApplicationDefinition;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.DeploymentDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.config.SecuritySettings;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gemeinsames Testgeruest fuer die Fixtureadapter.
 *
 * <p>Die Szenarien liegen im Repository unter {@code fixtures/} und ausdruecklich nicht unter
 * {@code src/test/resources/}. Das Projektverzeichnis wird ueber die Systemeigenschaft
 * {@code opsagent.projectDir} gefunden, die {@code build.gradle} im Testlauf setzt. Fehlt das
 * Verzeichnis, werden die betroffenen Tests uebersprungen.</p>
 *
 * <p>Das Testgeruest baut die Git-Arbeitsbaeume der Szenarien zur Laufzeit in einem
 * temporaeren Verzeichnis auf ({@code repo/repo-setup.json} beschreibt Reihenfolge und
 * Nachrichten). Ein eingechecktes {@code .git}-Verzeichnis gibt es bewusst nicht.</p>
 */
final class FixtureHarness {

    /** Synthetischer Namespace der ersten Anwendung. */
    static final String NAMESPACE = "fixture-integration";

    /** Synthetischer Namespace der zweiten Anwendung. */
    static final String NAMESPACE_SERVICE_B = "fixture-service-b-integration";

    private static final Path PROJECT_DIR = projectDir();
    private static final Path FIXTURES_ROOT = PROJECT_DIR == null ? null : PROJECT_DIR.resolve("fixtures");
    private static final Map<String, Path> GIT_WORKSPACES = new LinkedHashMap<>();

    private FixtureHarness() {
    }

    static Path fixturesRoot() {
        assumeTrue(FIXTURES_ROOT != null && Files.isDirectory(FIXTURES_ROOT),
                "Fixture-Szenarien unter fixtures/ sind nicht vorhanden; Test wird uebersprungen.");
        return FIXTURES_ROOT;
    }

    static Path projectDir() {
        String configured = System.getProperty("opsagent.projectDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        Path candidate = current.resolve("fixtures");
        return Files.isDirectory(candidate) ? current : null;
    }

    /** Alle Szenariokennungen, alphabetisch sortiert. */
    static List<String> scenarioIds() {
        Path root = fixturesRoot();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(FixtureData::isValidScenarioId)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Fixture-Wurzelverzeichnis ist nicht lesbar.", ex);
        }
    }

    static Path scenarioDir(String scenarioId) {
        Path dir = fixturesRoot().resolve(scenarioId);
        assumeTrue(Files.isDirectory(dir), "Szenario " + scenarioId + " fehlt; Test wird uebersprungen.");
        return dir;
    }

    static FixtureData scenarioData(String scenarioId, JsonMapper mapper) {
        return new FixtureData(scenarioDir(scenarioId), mapper);
    }

    static JsonMapper mapper() {
        return JsonMapper.builder().build();
    }

    /** Zeitquelle des Fixturebetriebs: fest, damit Ablaeufe reproduzierbar bleiben. */
    static Clock clock(String scenarioId, JsonMapper mapper) {
        JsonNode descriptor = scenarioData(scenarioId, mapper).readJson("scenario.json");
        JsonNode fixedNow = descriptor.get("fixedNow");
        Instant instant = fixedNow != null && fixedNow.isString()
                ? Instant.parse(fixedNow.asString())
                : Instant.parse("2026-10-01T10:10:00Z");
        return Clock.fixed(instant, java.time.ZoneOffset.UTC);
    }

    // --- Konfiguration ----------------------------------------------------------------------

    /** Synthetische Konfiguration fuer ein Szenario (ohne Git-Arbeitsbaum). */
    static OpsConfig config(String scenarioId) {
        return config(scenarioId, null);
    }

    /**
     * Synthetische Konfiguration fuer ein Szenario.
     *
     * <p>Anwendung, Umgebung und erlaubte Dateien stammen aus dem Block {@code _testConfig} in
     * {@code scenario.json}; dadurch bleibt jede Szenariokonfiguration an einer Stelle und die
     * Fixtureadapter werden nicht mit einem Sonderfall je Szenario verdrahtet.</p>
     *
     * @param gitCheckout Arbeitsbaum des Szenarios oder {@code null} fuer die reine
     *                    {@code repo-files}-Aufloesung
     */
    static OpsConfig config(String scenarioId, Path gitCheckout) {
        return configFromRoot(fixturesRoot(), scenarioId, gitCheckout);
    }

    private static OpsConfig configFromRoot(Path root, String scenarioId, Path gitCheckout) {
        FixtureData scenario = new FixtureData(root.resolve(scenarioId), mapper());
        JsonNode testConfig = scenario.readJson("scenario.json").get("_testConfig");
        Path storage = PROJECT_DIR.resolve("build").resolve("fixture-test-state").resolve(scenarioId);
        // Ein Szenario kann verlangen, dass der Git-Arbeitsbaum zur Laufzeit in einem temporaeren
        // Verzeichnis aufgebaut wird (repoCheckout). Dann zeigen die Leser auf diesen Arbeitsbaum.
        String checkoutMode = strings(testConfig, "repoCheckout", List.of()).stream().findFirst().orElse("");
        Path localPath;
        if (gitCheckout != null) {
            localPath = gitCheckout;
        } else if ("runtime-git".equals(checkoutMode)) {
            localPath = gitWorkspace(scenarioId, mapper());
        } else {
            localPath = root.resolve(scenarioId).resolve("repo");
        }

        List<String> templateFiles = strings(testConfig, "templateFiles",
                List.of("t01-liveness-startup/repo/templates/modelfinder.yaml"));
        List<String> allowedByScenario = strings(testConfig, "allowedParameterNames",
                List.of("IMAGE_TAG", "PROBE_PATH", "INITIAL_DELAY_SECONDS", "FAILURE_THRESHOLD"));
        List<String> parameterFiles = strings(testConfig, "parameterFiles",
                List.of("t01-liveness-startup/repo/env/integration.env"));
        List<String> allowedParameters = strings(testConfig, "allowedParameterNames",
                List.of("IMAGE_TAG", "PROBE_PATH", "INITIAL_DELAY_SECONDS", "FAILURE_THRESHOLD"));

        ConfigurationDefinition configuration = new ConfigurationDefinition(
                "deployment-config", templateFiles, parameterFiles, allowedParameters,
                new ConfigurationDefinition.RevisionDefinition(
                        ConfigurationDefinition.RevisionDefinition.Source.JENKINS_SCM,
                        "sogis/openshift-templates", null, null));

        Map<String, EnvironmentDefinition> serviceBEnvironments = new LinkedHashMap<>();
        serviceBEnvironments.put("integration", new EnvironmentDefinition(
                "fixture-cluster", NAMESPACE_SERVICE_B,
                new WorkloadDefinition(WorkloadDefinition.WorkloadKind.DEPLOYMENT, "fixture-service-b",
                        List.of("service-b"), false),
                new DeploymentDefinition("apps-deploy", List.of("fixture-folder", "service-b")),
                configuration));
        serviceBEnvironments.put("legacy", new EnvironmentDefinition(
                "fixture-cluster", NAMESPACE_SERVICE_B,
                new WorkloadDefinition(WorkloadDefinition.WorkloadKind.DEPLOYMENT_CONFIG,
                        "fixture-legacy-application", List.of("legacy-application"), false),
                new DeploymentDefinition("apps-deploy", List.of("fixture-folder", "legacy-application")),
                configuration));

        Map<String, ApplicationDefinition> applications = new LinkedHashMap<>();
        applications.put("modelfinder", new ApplicationDefinition("modelfinder", "Modelfinder (synthetisch)",
                Map.of("integration", new EnvironmentDefinition(
                        "fixture-cluster", NAMESPACE,
                        new WorkloadDefinition(WorkloadDefinition.WorkloadKind.DEPLOYMENT, "fixture-modelfinder",
                                List.of("modelfinder"), false),
                        new DeploymentDefinition("apps-deploy", List.of("fixture-folder", "modelfinder")),
                        configuration))));
        applications.put("fixture-service-b", new ApplicationDefinition("fixture-service-b",
                "Fixture Service B (synthetisch)", serviceBEnvironments));

        List<String> allowedFiles = new ArrayList<>();
        allowedFiles.addAll(templateFiles);
        allowedFiles.addAll(parameterFiles);

        return new OpsConfig(
                1,
                DataMode.FIXTURE,
                root.resolve(scenarioId).resolve("scenario.json"),
                storage,
                new SecuritySettings(false, SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY,
                        SecuritySettings.IDENTITY_RESTRICTED),
                collectTimeoutLimits(),
                Map.of("fixture-cluster", new ClusterDefinition("fixture-cluster",
                        "https://fixture.invalid:6443", Path.of("/bin/false"), Path.of("/dev/null"),
                        "fixture-context", SecuritySettings.IDENTITY_RESTRICTED,
                        List.of("fixture.invalid"), List.of(NAMESPACE, NAMESPACE_SERVICE_B))),
                Map.of("apps-deploy", new JenkinsDefinition("apps-deploy", "https://jenkins.fixture.invalid",
                        "fixture-reader", new JenkinsDefinition.SecretRef(null, "OPS_AGENT_FIXTURE_TOKEN"),
                        new JenkinsDefinition.TlsSettings(null),
                        new JenkinsDefinition.ProxySettings(JenkinsDefinition.ProxySettings.MODE_DIRECT,
                                null, null, null, null),
                        List.of(List.of("fixture-folder", "modelfinder"),
                                List.of("fixture-folder", "service-b"),
                                List.of("fixture-folder", "legacy-application")),
                        List.of("TARGET_NAMESPACE", "IMAGE_TAG", "CONFIG_REVISION"))),
                Map.of("deployment-config", new RepositoryDefinition("deployment-config",
                        "sogis/openshift-templates", localPath, List.copyOf(allowedFiles))),
                applications,
                new ch.so.agi.ops.config.FixtureSettings(root, scenarioId, "fixed", "2026-10-01T10:10:00Z"));
    }

    /** Ressourcengrenzen des Fixturebetriebs; das Console-Log ist bewusst klein budgetiert. */
    private static Limits collectTimeoutLimits() {
        Limits defaults = Limits.defaults();
        return new Limits(defaults.collectTimeoutSeconds(), defaults.requestTimeoutSeconds(),
                defaults.connectTimeoutSeconds(), defaults.maxParallelRequests(), defaults.maxRequests(),
                defaults.maxPods(), defaults.maxEvents(), defaults.maxGitFiles(),
                4096L, defaults.maxContainerLogBytes(), defaults.maxTotalInputBytes(),
                defaults.maxEvidenceResponseBytes(), defaults.maxReportDraftBytes());
    }

    private static List<String> strings(JsonNode node, String field, List<String> fallback) {
        if (node == null) {
            return List.copyOf(fallback);
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.copyOf(fallback);
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isString()) {
                result.add(item.asString());
            }
        });
        return List.copyOf(result);
    }

    /**
     * Konfiguration mit einer abweichenden Fixturewurzel und einem abweichenden Bytebudget des
     * Console-Logs. Damit pruefen Tests Fehlervarianten, ohne die eingecheckten Szenarien zu
     * veraendern.
     */
    static OpsConfig configAt(Path fixtureRoot, String scenarioId, long maxBuildLogBytes) {
        OpsConfig base = configFromRoot(fixtureRoot, scenarioId, null);
        Limits limits = base.limits();
        return new OpsConfig(base.schemaVersion(), base.mode(), base.sourceFile(), base.storageRoot(),
                base.security(), new Limits(limits.collectTimeoutSeconds(), limits.requestTimeoutSeconds(),
                        limits.connectTimeoutSeconds(), limits.maxParallelRequests(), limits.maxRequests(),
                        limits.maxPods(), limits.maxEvents(), limits.maxGitFiles(), maxBuildLogBytes,
                        limits.maxContainerLogBytes(), limits.maxTotalInputBytes(),
                        limits.maxEvidenceResponseBytes(), limits.maxReportDraftBytes()),
                base.clusters(), base.jenkins(), base.repositories(), base.applications(), base.fixture());
    }

    /** Fixtureadapter mit einer abweichenden Fixturewurzel. */
    static FixtureReaders readersAt(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return new FixtureReaders(config, Clock.fixed(Instant.parse("2026-10-01T10:10:00Z"),
                java.time.ZoneOffset.UTC), mapper);
    }

    static BuildReader buildReaderAt(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return readersAt(config, scenarioId, mapper).buildReader("apps-deploy");
    }

    /** Konfiguration mit abweichendem Bytebudget des Console-Logs. */
    static OpsConfig configWithBuildLogBudget(String scenarioId, long maxBuildLogBytes) {
        OpsConfig base = config(scenarioId);
        Limits limits = base.limits();
        return new OpsConfig(base.schemaVersion(), base.mode(), base.sourceFile(), base.storageRoot(),
                base.security(), new Limits(limits.collectTimeoutSeconds(), limits.requestTimeoutSeconds(),
                        limits.connectTimeoutSeconds(), limits.maxParallelRequests(), limits.maxRequests(),
                        limits.maxPods(), limits.maxEvents(), limits.maxGitFiles(), maxBuildLogBytes,
                        limits.maxContainerLogBytes(), limits.maxTotalInputBytes(),
                        limits.maxEvidenceResponseBytes(), limits.maxReportDraftBytes()),
                base.clusters(), base.jenkins(), base.repositories(), base.applications(), base.fixture());
    }

    // --- Ziel und Adapter -------------------------------------------------------------------

    static ResolvedTarget target(String scenarioId, OpsConfig config) {
        ApplicationDefinition application = config.application(applicationIdOf(scenarioId));
        String environment = environmentOf(scenarioId);
        EnvironmentDefinition definition = application.environment(environment);
        Instant now = Instant.parse("2026-10-01T10:10:00Z");
        CollectionBudget budget = CollectionBudget.of(Duration.ofSeconds(config.limits().collectTimeoutSeconds()),
                now, config.limits().maxRequests(), config.limits().maxPods(), config.limits().maxEvents(),
                config.limits().maxTotalInputBytes(), config.limits().maxEvidenceResponseBytes());
        return new ResolvedTarget("TGT-fixture-test", config, DataMode.FIXTURE, application.id(), environment,
                definition, buildRefOf(application.id(), definition), null, now, now.plus(Duration.ofMinutes(30)),
                budget, List.of(), scenarioId);
    }

    /** Anwendungskennung eines Szenarios aus dessen Testkonfiguration. */
    static String applicationIdOf(String scenarioId) {
        return testConfigValue(scenarioId, "application", scenarioId.startsWith("t10")
                ? "fixture-service-b"
                : "modelfinder");
    }

    private static String environmentOf(String scenarioId) {
        return testConfigValue(scenarioId, "environment", "integration");
    }

    private static String testConfigValue(String scenarioId, String field, String fallback) {
        JsonNode node = new FixtureData(scenarioDir(scenarioId), mapper()).readJson("scenario.json")
                .get("_testConfig");
        if (node == null || node.get(field) == null || !node.get(field).isString()) {
            return fallback;
        }
        return node.get(field).asString();
    }

    private static BuildRef buildRefOf(String applicationId, EnvironmentDefinition definition) {
        return new BuildRef(definition.deployment().jenkins(), definition.deployment().jobPath(), 42);
    }

    static FixtureReaders readers(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return new FixtureReaders(config, clock(scenarioId, mapper), mapper);
    }

    static ClusterReader clusterReader(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return readers(config, scenarioId, mapper).clusterReader();
    }

    static BuildReader buildReader(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return readers(config, scenarioId, mapper).buildReader("apps-deploy");
    }

    static ConfigurationReader configurationReader(OpsConfig config, String scenarioId, JsonMapper mapper) {
        return readers(config, scenarioId, mapper).configurationReader();
    }

    /** Liest die Konfiguration eines Szenarios ueber den Fixtureadapter. */
    static SourceResult<ResolvedConfiguration> readConfiguration(String scenarioId, Path gitCheckout,
            JsonMapper mapper) {
        OpsConfig config = config(scenarioId, gitCheckout);
        ResolvedTarget target = target(scenarioId, config);
        ConfigurationReader reader = configurationReader(config, scenarioId, mapper);
        return reader.collect(target, revisionOf(scenarioId, mapper), target.budget());
    }

    /** Liest die Konfiguration eines Szenarios mit einer vorgegebenen Revisionsherkunft. */
    static SourceResult<ResolvedConfiguration> readConfigurationWithRevision(String scenarioId,
            ch.so.agi.ops.domain.git.RevisionProvenance revision, JsonMapper mapper) {
        OpsConfig config = config(scenarioId);
        ResolvedTarget target = target(scenarioId, config);
        return configurationReader(config, scenarioId, mapper).collect(target, revision, target.budget());
    }

    /**
     * Revisionsherkunft eines Szenarios, gelesen aus {@code repo/repo-setup.json}.
     *
     * <p>Verwendet wird der als {@code head} markierte Commit; fehlt eine solche Markierung,
     * der letzte Commit. Das entspricht der Zuordnung "Jenkins hat diesen Commit verwendet".</p>
     */
    static ch.so.agi.ops.domain.git.RevisionProvenance revisionOf(String scenarioId, JsonMapper mapper) {
        FixtureData scenario = scenarioData(scenarioId, mapper);
        JsonNode descriptor = scenario.readJson("scenario.json");
        JsonNode configured = descriptor.get("revisionCommit");
        String commit = configured != null && configured.isString() ? configured.asString() : null;
        if (commit == null && scenario.has("repo/repo-setup.json")) {
            // Ohne ausdrueckliche Angabe gilt der Commit, den Jenkins laut Szenario verwendet hat:
            // der aelteste Stand des Arbeitsbaums. Der als head markierte Stand ist der heutige
            // Checkout und ausdruecklich kein Ersatz dafuer.
            JsonNode setup = scenario.readJson("repo/repo-setup.json");
            JsonNode commits = setup.get("commits");
            if (commits != null && commits.isArray() && commits.size() > 0) {
                JsonNode chosen = commits.get(0);
                for (JsonNode candidate : commits) {
                    JsonNode head = candidate.get("head");
                    if (head == null || !head.isBoolean() || !head.asBoolean()) {
                        chosen = candidate;
                        break;
                    }
                }
                commit = chosen.get("sha") == null || chosen.get("sha").isNull()
                        ? null
                        : chosen.get("sha").asString();
            }
        }
        if (commit == null) {
            // Szenarien ohne Revisionsdaten: die Revision bleibt ausdruecklich unbekannt,
            // es wird kein Ersatzcommit erfunden.
            return ch.so.agi.ops.domain.git.RevisionProvenance.unknown("sogis/openshift-templates",
                    "Das Szenario enthaelt keine Revisionsangabe.");
        }
        return new ch.so.agi.ops.domain.git.RevisionProvenance("sogis/openshift-templates", commit,
                commit.length() < 8 ? commit : commit.substring(0, 8),
                ch.so.agi.ops.domain.git.RevisionProvenance.Source.JENKINS_SCM, 42, "jenkins-metadata",
                "Synthetische Fixturezuordnung; kein Livebeleg.");
    }

    // --- Ergebnisse -------------------------------------------------------------------------

    static ClusterCollection cluster(String scenarioId, JsonMapper mapper) {
        OpsConfig config = config(scenarioId);
        SourceResult<ClusterCollection> result = clusterReader(config, scenarioId, mapper)
                .collect(target(scenarioId, config), target(scenarioId, config).budget());
        return result.data();
    }

    static SourceResult<ClusterCollection> clusterResult(String scenarioId, JsonMapper mapper) {
        OpsConfig config = config(scenarioId);
        ResolvedTarget target = target(scenarioId, config);
        return clusterReader(config, scenarioId, mapper).collect(target, target.budget());
    }

    static BuildSnapshot build(String scenarioId, JsonMapper mapper) {
        OpsConfig config = config(scenarioId);
        ResolvedTarget target = target(scenarioId, config);
        SourceResult<BuildSnapshot> result = buildReader(config, scenarioId, mapper)
                .collectMetadata(target, target.budget());
        assumeTrue(result.data() != null, "Keine Buildmetadaten im Szenario " + scenarioId);
        return result.data();
    }

    static ConsoleLogEvidence consoleLog(String scenarioId, JsonMapper mapper) {
        OpsConfig config = config(scenarioId);
        ResolvedTarget target = target(scenarioId, config);
        SourceResult<ConsoleLogEvidence> result = buildReader(config, scenarioId, mapper)
                .collectConsoleLog(target, target.budget());
        assumeTrue(result.data() != null && result.data().lines() != null,
                "Kein Console-Log im Szenario " + scenarioId);
        return result.data();
    }

    // --- Mechanische Befunde ----------------------------------------------------------------

    /** Probe-Konfiguration aus dem Pod, so wie sie der Pod tatsaechlich ausfuehrt. */
    static Map<String, List<ProbeConfig>> probeConfigFromPods(ClusterCollection cluster) {
        Map<String, List<ProbeConfig>> result = new LinkedHashMap<>();
        for (PodSnapshot pod : cluster.pods()) {
            for (ProbeConfig probe : pod.probesFromPod()) {
                result.computeIfAbsent(probe.containerName(), key -> new ArrayList<>()).add(probe);
            }
        }
        return result;
    }

    /**
     * Belegstaerke der Zuordnung Build -&gt; Workload.
     *
     * <p>Die Regel ist der verbindliche Vertrag aus {@code docs/spec-v1/SPEZIFIKATION_V1.md}
     * D-02: mehrere plausible Revisionen mit gleichem Image-Tag ergeben keinen exakten
     * Nachweis. Sie wird hier unabhaengig nachgebildet, damit der Oracle-Test die
     * Linkstaerke pruefen kann, ohne den Application Service zu starten.</p>
     */
    static LinkStrength buildToWorkloadLink(ClusterCollection cluster, BuildSnapshot build) {
        if (cluster.workload() == null) {
            return LinkStrength.UNLINKED;
        }
        // Mehrdeutigkeit ueber die Owner-UID der Revisionen: mehrere plausible Revisionen mit
        // gleichem Image-Tag ergeben keinen exakten Nachweis.
        if (revisionsOwnedByWorkload(cluster).size() > 1) {
            return LinkStrength.AMBIGUOUS;
        }
        return imagesMatch(cluster, build) ? LinkStrength.EXACT : LinkStrength.PROBABLE;
    }

    /**
     * Image-Vergleich zwischen Build und erfassten Containern.
     *
     * <p>Der Build nennt haeufig nur den Tag (z. B. {@code 2026.10.1}), das Pod-Image dagegen
     * die vollstaendige Referenz. Verglichen wird deshalb auch der Tag-Anteil; ein gleicher Tag
     * allein bleibt in {@link #buildToWorkloadLink} eine wahrscheinliche, keine exakte Zuordnung,
     * sobald mehrere Revisionen im Spiel sind.</p>
     */
    private static boolean imagesMatch(ClusterCollection cluster, BuildSnapshot build) {
        if (build == null || build.imageReferences().isEmpty()) {
            return false;
        }
        List<String> podImages = cluster.pods().stream()
                .flatMap(pod -> pod.containers().stream())
                .map(ContainerSnapshot::image)
                .filter(java.util.Objects::nonNull)
                .toList();
        for (String reference : build.imageReferences()) {
            String tag = tagOf(reference);
            for (String image : podImages) {
                if (image.equals(reference)) {
                    return true;
                }
                if (!tag.isEmpty() && (image.endsWith(":" + tag) || image.equals(tag))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tag-Anteil einer Image-Referenz; eine reine Tagangabe wird unveraendert zurueckgegeben. */
    private static String tagOf(String image) {
        int colon = image.lastIndexOf(':');
        int slash = image.lastIndexOf('/');
        if (colon > slash) {
            return image.substring(colon + 1);
        }
        return slash < 0 ? image : "";
    }

    static List<ContainerSnapshot> containersOf(ClusterCollection cluster, String containerName) {
        return cluster.pods().stream()
                .flatMap(pod -> pod.containers().stream())
                .filter(container -> container.name().equals(containerName))
                .toList();
    }

    /**
     * Revisionen, deren Controller-Owner exakt die UID des Workloads traegt.
     *
     * <p>Bewusst ueber {@link ClusterCollection#revisionsOwnedBy(String)}: diese Methode
     * vergleicht ausschliesslich die Owner-UID und ist damit die verbindliche Zugehoerigkeitsregel.
     * {@code OwnershipGraph.revisionsOf} vergleicht zusaetzlich das Owner-Kind mit dem
     * Revisions-Kind und liefert deshalb keine Revision.</p>
     */
    static List<RevisionSnapshot> revisionsOwnedByWorkload(ClusterCollection cluster) {
        return cluster.workload() == null
                ? List.of()
                : cluster.revisionsOwnedBy(cluster.workload().uid());
    }

    // --- Git-Arbeitsbaeume der Szenarien ---------------------------------------------------

    /**
     * Baut den Git-Arbeitsbaum eines Szenarios zur Laufzeit auf und liefert den Pfad.
     *
     * <p>Der Aufbau ist deterministisch (feste Autor-/Committerdaten aus
     * {@code repo-setup.json}), damit die Commits in den Rohantworten des Szenarios
     * wiederauffindbar sind. Ist Git nicht verfuegbar oder weicht ein berechneter Commit von
     * der Szenarioangabe ab, wird der Test uebersprungen statt falsch zu bestehen.</p>
     */
    static synchronized Path gitWorkspace(String scenarioId, JsonMapper mapper) {
        Path cached = GIT_WORKSPACES.get(scenarioId);
        if (cached != null) {
            return cached;
        }
        FixtureData scenario = scenarioData(scenarioId, mapper);
        JsonNode setup = scenario.readJson("repo/repo-setup.json");
        Path dest;
        try {
            dest = Files.createTempDirectory("fixture-repo-" + scenarioId + "-");
        } catch (IOException ex) {
            throw new IllegalStateException("Temporaeres Verzeichnis nicht anlegbar.", ex);
        }
        assumeTrue(gitAvailable(), "Git ist nicht verfuegbar; der Git-Pfad wird uebersprungen.");
        run(dest, "init", "-q", "-b", "main");

        JsonNode commits = setup.get("commits");
        List<String> expected = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (JsonNode commit : commits) {
            String id = commit.get("id").asString();
            String message = commit.get("message").asString();
            String date = commit.get("date").asString();
            Path source = scenario.resolve("repo/commits/" + id);
            assumeTrue(Files.isDirectory(source), "Revisionsstand " + id + " fehlt im Szenario.");
            clearWorkingTree(dest);
            copyTree(source, dest);
            commitAll(dest, message, date);
            actual.add(run(dest, "rev-parse", "HEAD").trim());
            expected.add(commit.get("sha") == null || commit.get("sha").isNull()
                    ? null
                    : commit.get("sha").asString());
        }
        for (int index = 0; index < expected.size(); index++) {
            String want = expected.get(index);
            assumeTrue(want == null || want.equals(actual.get(index)),
                    "Der zur Laufzeit erzeugte Commit " + actual.get(index) + " weicht von der Szenarioangabe "
                            + want + " ab; die Fixturen muessten neu erzeugt werden. Test wird uebersprungen.");
        }
        GIT_WORKSPACES.put(scenarioId, dest);
        return dest;
    }

    private static boolean gitAvailable() {
        try {
            return run(Path.of(""), "--version") != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void clearWorkingTree(Path dest) {
        try (Stream<Path> stream = Files.walk(dest)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/.git/"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ex) {
                            throw new IllegalStateException("Arbeitsbaum nicht leerbar.", ex);
                        }
                    });
        } catch (IOException ex) {
            throw new IllegalStateException("Arbeitsbaum nicht lesbar.", ex);
        }
    }

    static void copyTree(Path source, Path dest) {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path target = dest.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Revisionsstand nicht kopierbar.", ex);
        }
    }

    private static void commitAll(Path dest, String message, String date) {
        env(dest, date, "add", "-A");
        env(dest, date, "commit", "-q", "-m", message);
    }

    private static String run(Path workdir, String... args) {
        return exec(workdir, Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"), args);
    }

    private static String env(Path workdir, String date, String... args) {
        return exec(workdir, Map.of(
                "GIT_AUTHOR_NAME", "Fixture Generator",
                "GIT_AUTHOR_EMAIL", "fixture@example.invalid",
                "GIT_COMMITTER_NAME", "Fixture Generator",
                "GIT_COMMITTER_EMAIL", "fixture@example.invalid",
                "GIT_AUTHOR_DATE", date,
                "GIT_COMMITTER_DATE", date,
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1"), args);
    }

    private static String exec(Path workdir, Map<String, String> extraEnv, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        if (!workdir.toString().isEmpty()) {
            builder.directory(workdir.toFile());
        }
        builder.environment().putAll(extraEnv);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " ist fehlgeschlagen: " + output);
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("git ist nicht ausfuehrbar.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git wurde unterbrochen.", ex);
        }
    }

    /** Setzt einen Wert in einer Kopie des Szenarios in einem temporaeren Verzeichnis. */
    static Path copyScenarioWith(String scenarioId, String jsonPointerField, String value, JsonMapper mapper) {
        Path source = scenarioDir(scenarioId);
        Path dest;
        try {
            dest = Files.createTempDirectory("fixture-scenario-" + scenarioId + "-");
            copyTree(source, dest);
            Path descriptor = dest.resolve("scenario.json");
            JsonNode root = mapper.readTree(Files.readAllBytes(descriptor));
            ((tools.jackson.databind.node.ObjectNode) root).put(jsonPointerField, value);
            Files.writeString(descriptor, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException("Szenariokopie nicht anlegbar.", ex);
        }
        return dest;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
