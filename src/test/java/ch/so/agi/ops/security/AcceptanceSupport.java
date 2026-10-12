package ch.so.agi.ops.security;

import ch.so.agi.ops.application.ApplicationCatalog;
import ch.so.agi.ops.application.ClockProvider;
import ch.so.agi.ops.application.InvestigationService;
import ch.so.agi.ops.application.TargetBuilder;
import ch.so.agi.ops.application.TargetRegistry;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ApplicationDefinition;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.ConfigLoader;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.DeploymentDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.FixtureSettings;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.config.SecuritySettings;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.evidence.FileSnapshotStore;
import ch.so.agi.ops.mcp.EvidenceReadBudget;
import ch.so.agi.ops.mcp.OpsTools;
import ch.so.agi.ops.report.DiagnosisDocument;
import ch.so.agi.ops.report.FileReportStore;
import ch.so.agi.ops.snapshot.EvidenceIndexEntry;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.snapshot.SnapshotDraft;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.SourceSummary;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import ch.so.agi.ops.util.VersionInfo;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Gemeinsame, synthetische Hilfsmittel der Sicherheitsabnahme S01-S12.
 *
 * <p>Alle Werte sind erfunden. Es gibt keine Live-Adresse, kein echtes Credential, keinen
 * Netzwerkzugriff auf ein Firmensystem und keinen Aufruf eines echten {@code oc}. Die beiden
 * einzigen zulaessigen Beispielhosts sind {@code api.ocp.so.ch} und
 * {@code jenkins-agi-apps-production.apps.ocp.so.ch}; sie werden ausschliesslich als
 * Konfigurationstext verwendet und nie aufgeloest.</p>
 */
final class AcceptanceSupport {

    static final String APP_ID = "fixture-app";
    static final String ENVIRONMENT = "integration";
    static final String CLUSTER_ID = "fixture-cluster";
    static final String JENKINS_ID = "fixture-jenkins";
    static final String NAMESPACE = "fixture-integration";
    static final List<String> JOB_PATH = List.of("fixture-folder", "fixture-app");
    static final String OC_SERVER = "https://api.ocp.so.ch:6443";
    static final String JENKINS_BASE_URL = "https://jenkins-agi-apps-production.apps.ocp.so.ch";
    static final Instant FIXED_NOW = Instant.parse("2026-10-01T10:10:00Z");

    /** Canary-Wert fuer die Redaktionstests; bewusst kein echtes Secret. */
    static final String CANARY = "C4n4ryT0kenValue0123456789AbCdEf";

    private AcceptanceSupport() {
    }

    // ------------------------------------------------------------------
    // Konfiguration
    // ------------------------------------------------------------------

    /** Vollstaendige, synthetische Fixturekonfiguration mit der ueblichen Allowlist. */
    static OpsConfig inMemoryConfig(Path storageRoot) {
        return inMemoryConfig(storageRoot, NAMESPACE, List.of(NAMESPACE), JOB_PATH, List.of(JOB_PATH));
    }

    /**
     * Fixturekonfiguration mit frei gesetzten Allowlists. Wird direkt als Datensatz gebaut,
     * damit auch eine Konfiguration pruefbar bleibt, die der strenge Loader ablehnen wuerde
     * (genau das ist der Gegenstand von S01).
     */
    static OpsConfig inMemoryConfig(Path storageRoot, String namespace, List<String> allowedNamespaces,
            List<String> jobPath, List<List<String>> allowedJobPaths) {
        return inMemoryConfig(storageRoot, namespace, allowedNamespaces, jobPath, allowedJobPaths,
                JENKINS_BASE_URL, Limits.defaults().maxBuildLogBytes(), storageRoot.resolve("kubeconfig-fixture"));
    }

    /** Wie oben, zusaetzlich mit frei gesetzter Jenkins-Basisadresse, Logbudget und kubeconfig. */
    static OpsConfig inMemoryConfig(Path storageRoot, String namespace, List<String> allowedNamespaces,
            List<String> jobPath, List<List<String>> allowedJobPaths, String jenkinsBaseUrl, long maxBuildLogBytes,
            Path kubeconfig) {
        return buildConfig(storageRoot, namespace, allowedNamespaces, jobPath, allowedJobPaths, jenkinsBaseUrl,
                maxBuildLogBytes, kubeconfig, DataMode.FIXTURE);
    }

    /**
     * Livemodus-Konfiguration fuer die Pruefung der Vorpruefungen des echten Clusteradapters.
     * Der Adapter startet im Fixturemodus bewusst kein {@code oc}; die kubeconfig-Pruefung
     * laeuft nur im Livemodus. Es wird dabei kein Prozess gestartet, weil die Vorpruefung
     * abbricht.
     */
    static OpsConfig liveConfig(Path storageRoot, String namespace, List<String> allowedNamespaces,
            List<String> jobPath, List<List<String>> allowedJobPaths, String jenkinsBaseUrl, long maxBuildLogBytes,
            Path kubeconfig) {
        return buildConfig(storageRoot, namespace, allowedNamespaces, jobPath, allowedJobPaths, jenkinsBaseUrl,
                maxBuildLogBytes, kubeconfig, DataMode.LIVE);
    }

    private static OpsConfig buildConfig(Path storageRoot, String namespace, List<String> allowedNamespaces,
            List<String> jobPath, List<List<String>> allowedJobPaths, String jenkinsBaseUrl, long maxBuildLogBytes,
            Path kubeconfig, DataMode mode) {
        Path base = storageRoot.toAbsolutePath().normalize();
        Limits defaults = Limits.defaults();
        Limits limits = new Limits(defaults.collectTimeoutSeconds(), defaults.requestTimeoutSeconds(),
                defaults.connectTimeoutSeconds(), defaults.maxParallelRequests(), defaults.maxRequests(),
                defaults.maxPods(), defaults.maxEvents(), defaults.maxGitFiles(), maxBuildLogBytes,
                defaults.maxContainerLogBytes(), defaults.maxTotalInputBytes(), defaults.maxEvidenceResponseBytes(),
                defaults.maxReportDraftBytes());
        ClusterDefinition cluster = new ClusterDefinition(CLUSTER_ID, OC_SERVER, base.resolve("oc-fixture"),
                kubeconfig, "fixture-context", SecuritySettings.IDENTITY_RESTRICTED, List.of("api.ocp.so.ch"),
                List.copyOf(allowedNamespaces));
        JenkinsDefinition jenkins = new JenkinsDefinition(JENKINS_ID, jenkinsBaseUrl, "fixture-reader",
                new JenkinsDefinition.SecretRef(base.resolve("jenkins-token-fixture"), null),
                new JenkinsDefinition.TlsSettings(null),
                new JenkinsDefinition.ProxySettings(JenkinsDefinition.ProxySettings.MODE_DIRECT, null, null, null,
                        null),
                List.copyOf(allowedJobPaths), List.of("TARGET_NAMESPACE"));
        RepositoryDefinition repository = new RepositoryDefinition("fixture-config", "sogis/openshift-templates",
                base.resolve("repo-fixture"), List.of("fixture/templates/app.yaml", "fixture/env/integration.env"));
        EnvironmentDefinition environment = new EnvironmentDefinition(CLUSTER_ID, namespace,
                new WorkloadDefinition(WorkloadDefinition.WorkloadKind.DEPLOYMENT, "fixture-app", List.of("app"),
                        false),
                new DeploymentDefinition(JENKINS_ID, List.copyOf(jobPath)),
                new ConfigurationDefinition("fixture-config", List.of("fixture/templates/app.yaml"),
                        List.of("fixture/env/integration.env"), List.of("INITIAL_DELAY_SECONDS"),
                        new ConfigurationDefinition.RevisionDefinition(
                                ConfigurationDefinition.RevisionDefinition.Source.JENKINS_SCM,
                                "sogis/openshift-templates", null, null)));
        ApplicationDefinition application = new ApplicationDefinition(APP_ID, "Fixture App (synthetisch)",
                Map.of(ENVIRONMENT, environment));
        return new OpsConfig(OpsConfig.SUPPORTED_SCHEMA_VERSION, mode, base.resolve("fixture.yaml"), base,
                new SecuritySettings(false, SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY,
                        SecuritySettings.IDENTITY_RESTRICTED),
                limits, Map.of(CLUSTER_ID, cluster), Map.of(JENKINS_ID, jenkins),
                Map.of("fixture-config", repository), Map.of(APP_ID, application),
                mode == DataMode.FIXTURE
                        ? new FixtureSettings(base.resolve("fixtures"), "t01-liveness-startup",
                                FixtureSettings.CLOCK_FIXED, FIXED_NOW.toString())
                        : null);
    }

    /**
     * Konfigurationstext fuer den strengen {@link ConfigLoader}. Die Anwendung verwendet den
     * uebergebenen Namespace und Jobpfad, die oberen Grenzen stehen in Cluster bzw. Jenkins.
     */
    static String configYaml(Path base, String namespace, List<String> allowedNamespaces, List<String> jobPath,
            List<List<String>> allowedJobPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("schemaVersion: 1\n");
        sb.append("mode: fixture\n");
        sb.append("storage:\n  root: \"").append(base.toAbsolutePath().normalize()).append("\"\n");
        sb.append("security:\n  allowPersonalCredentials: false\n");
        sb.append("  modelDataPolicy: internal-approved-only\n  identityMode: restricted\n");
        sb.append("limits:\n  collectTimeoutSeconds: 30\n  requestTimeoutSeconds: 5\n");
        sb.append("  connectTimeoutSeconds: 2\n  maxParallelRequests: 2\n  maxRequests: 20\n");
        sb.append("  maxPods: 4\n  maxEvents: 100\n  maxGitFiles: 4\n");
        sb.append("clusters:\n  ").append(CLUSTER_ID).append(":\n");
        sb.append("    server: \"").append(OC_SERVER).append("\"\n");
        sb.append("    ocExecutable: \"").append(base.resolve("oc-fixture")).append("\"\n");
        sb.append("    kubeconfig: \"").append(base.resolve("kubeconfig-fixture")).append("\"\n");
        sb.append("    context: \"fixture-context\"\n    identityMode: restricted\n");
        sb.append("    directHosts:\n      - api.ocp.so.ch\n");
        sb.append("    allowedNamespaces:\n");
        for (String allowed : allowedNamespaces) {
            sb.append("      - ").append(allowed).append('\n');
        }
        sb.append("jenkins:\n  ").append(JENKINS_ID).append(":\n");
        sb.append("    baseUrl: \"").append(JENKINS_BASE_URL).append("\"\n");
        sb.append("    username: fixture-reader\n    token:\n      environmentVar: OPS_AGENT_FIXTURE_TOKEN\n");
        sb.append("    proxy:\n      mode: direct\n    allowedJobPaths:\n");
        for (List<String> allowed : allowedJobPaths) {
            sb.append("      - [");
            for (int i = 0; i < allowed.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(allowed.get(i)).append('"');
            }
            sb.append("]\n");
        }
        sb.append("repositories:\n  fixture-config:\n    repositoryId: sogis/openshift-templates\n");
        sb.append("    localPath: \"").append(base.resolve("repo-fixture")).append("\"\n");
        sb.append("    allowedFiles:\n      - fixture/templates/app.yaml\n      - fixture/env/integration.env\n");
        sb.append("applications:\n  ").append(APP_ID).append(":\n    displayName: Fixture App (synthetisch)\n");
        sb.append("    environments:\n      ").append(ENVIRONMENT).append(":\n");
        sb.append("        cluster: ").append(CLUSTER_ID).append('\n');
        sb.append("        namespace: ").append(namespace).append('\n');
        sb.append("        workload:\n          kind: Deployment\n          name: fixture-app\n");
        sb.append("          containerNames:\n            - app\n");
        sb.append("        deployment:\n          jenkins: ").append(JENKINS_ID).append("\n          jobPath: [");
        for (int i = 0; i < jobPath.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(jobPath.get(i)).append('"');
        }
        sb.append("]\n");
        sb.append("        configuration:\n          repository: fixture-config\n");
        sb.append("          templateFiles:\n            - fixture/templates/app.yaml\n");
        sb.append("          parameterFiles:\n            - fixture/env/integration.env\n");
        sb.append("          allowedParameterNames:\n            - INITIAL_DELAY_SECONDS\n");
        sb.append("          revision:\n            source: jenkins-scm\n");
        sb.append("            repositoryId: sogis/openshift-templates\n");
        sb.append("fixture:\n  root: \"").append(base.resolve("fixtures")).append("\"\n");
        sb.append("  scenario: t01-liveness-startup\n  clock: fixed\n  fixedNow: \"2026-10-01T10:10:00Z\"\n");
        return sb.toString();
    }

    static Path writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static OpsConfig loadConfig(Path configFile) {
        return new ConfigLoader().load(configFile);
    }

    // ------------------------------------------------------------------
    // Verdrahtung (OpsTools ohne Netz und ohne echte Adapter)
    // ------------------------------------------------------------------

    /**
     * Vollstaendige Werkzeugverdrahtung im Fixturemodus. Jenkins- und Konfigurationsleser
     * fehlen bewusst; die Sammlung bleibt dadurch deterministisch und ohne Netz.
     */
    static Harness harness(OpsConfig config) {
        return harness(config, null, Limits.defaults().maxEvidenceResponseBytes());
    }

    static Harness harness(OpsConfig config, ClusterReader clusterReader, int evidenceReadBudgetBytes) {
        ClockProvider clock = ClockProvider.fixed(FIXED_NOW);
        JsonMapper mapper = YamlSupport.jsonMapper();
        Random random = new Random(20261012L);
        FileSnapshotStore snapshots = new FileSnapshotStore(config.storageRoot(), clock.clock(), random, mapper);
        FileReportStore reports = new FileReportStore(config.storageRoot(), clock.clock(), random);
        ApplicationCatalog catalog = new ApplicationCatalog(config);
        TargetBuilder targetBuilder = new TargetBuilder(config, clock);
        TargetRegistry registry = new TargetRegistry(clock);
        InvestigationService investigation = new InvestigationService(config, clock, snapshots, clusterReader,
                instanceId -> null, null, mapper, new VersionInfo("abnahme-test"));
        OpsTools tools = new OpsTools(config, clock, catalog, targetBuilder, registry, investigation, snapshots,
                reports, new EvidenceReadBudget(evidenceReadBudgetBytes), instanceId -> null, mapper,
                config.fixture() == null ? null : config.fixture().scenario());
        return new Harness(config, clock, mapper, snapshots, reports, tools);
    }

    /** Testverdrahtung mit den sechs Werkzeugen und den beiden lokalen Ablagen. */
    record Harness(OpsConfig config, ClockProvider clock, JsonMapper mapper, FileSnapshotStore snapshots,
            FileReportStore reports, OpsTools tools) {

        Path storageRoot() {
            return config.storageRoot();
        }

        SnapshotId save(List<RawEvidence> evidence) {
            return save(draft(evidence, Map.of("hinweis", "synthetischer Snapshot")));
        }

        SnapshotId save(SnapshotDraft draft) {
            return snapshots.save(draft).snapshotId();
        }

        StoredSnapshot read(SnapshotId id) {
            return snapshots.read(id);
        }

        /** Speichert einen Snapshot und liefert das gelesene Ergebnis zurueck. */
        StoredSnapshot saveAndRead(List<RawEvidence> evidence) {
            return read(save(evidence));
        }

        ResolvedTarget resolveTarget() {
            return new TargetBuilder(config, clock).build(APP_ID, ENVIRONMENT,
                    new BuildRef(JENKINS_ID, JOB_PATH, 42), config.fixture() == null ? null
                            : config.fixture().scenario(), List.of());
        }
    }

    /** Fixturekonfiguration fuer den echten Jenkins-Adapter gegen einen lokalen Testserver. */
    static OpsConfig jenkinsConfig(Path storageRoot, String jenkinsBaseUrl, long maxBuildLogBytes) {
        return jenkinsConfig(storageRoot, jenkinsBaseUrl, maxBuildLogBytes, DataMode.FIXTURE);
    }

    /** Wie oben, wahlweise im Livemodus (fuer die Vorpruefungen des Clusteradapters). */
    static OpsConfig jenkinsConfig(Path storageRoot, String jenkinsBaseUrl, long maxBuildLogBytes, DataMode mode) {
        OpsConfig config = buildConfig(storageRoot, NAMESPACE, List.of(NAMESPACE), JOB_PATH, List.of(JOB_PATH),
                jenkinsBaseUrl, maxBuildLogBytes, storageRoot.resolve("kubeconfig-fixture"), mode);
        writeFile(storageRoot.resolve("jenkins-token-fixture"), "fixture-token-value\n");
        return config;
    }

    /**
     * Konfiguration im Livemodus mit einer freigegebenen kubeconfig; die kubeconfig-Pruefung
     * des Clusteradapters laeuft nur im Livemodus.
     */
    static OpsConfig liveConfigMitKubeconfig(Path storageRoot, Path kubeconfig) {
        OpsConfig config = buildConfig(storageRoot, NAMESPACE, List.of(NAMESPACE), JOB_PATH, List.of(JOB_PATH),
                JENKINS_BASE_URL, Limits.defaults().maxBuildLogBytes(), kubeconfig, DataMode.LIVE);
        writeFile(storageRoot.resolve("jenkins-token-fixture"), "fixture-token-value\n");
        return config;
    }

    /** Aufgeloestes Ziel fuer den Jenkins-Adapter (Build 42, erlaubter Jobpfad). */
    static ResolvedTarget targetForJenkins(OpsConfig config) {
        return new TargetBuilder(config, ClockProvider.fixed(FIXED_NOW)).build(APP_ID, ENVIRONMENT,
                new BuildRef(JENKINS_ID, JOB_PATH, 42), config.isFixture() ? "t01-liveness-startup" : null,
                List.of());
    }

    /** Auftragsbudget mit denselben Grenzen wie die Fixturekonfiguration. */
    static CollectionBudget budget() {
        return CollectionBudget.of(java.time.Duration.ofSeconds(30), FIXED_NOW, 60, 4, 100, 32L * 1024 * 1024,
                32768);
    }

    /** Liest das Console-Log ueber den echten Jenkins-Adapter. */
    static ch.so.agi.ops.domain.SourceResult<ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence> consoleLog(
            OpsConfig config) {
        ch.so.agi.ops.adapter.jenkins.JenkinsHttpReader reader = new ch.so.agi.ops.adapter.jenkins.JenkinsHttpReader(
                config, JENKINS_ID, ClockProvider.fixed(FIXED_NOW).clock(), YamlSupport.jsonMapper());
        return reader.collectConsoleLog(targetForJenkins(config), budget());
    }

    /** Synthetisches Console-Log mit einem wichtigen Fehler nahe dem Ende. */
    static byte[] consoleLogMitFehlerAmEnde(int zeilen, String fehlerzeile) {
        StringBuilder sb = new StringBuilder(zeilen * 64);
        for (int i = 1; i <= zeilen - 2; i++) {
            sb.append("2026-10-01T10:00:00Z INFO Schritt ").append(i).append(" abgeschlossen\n");
        }
        sb.append(fehlerzeile).append('\n');
        sb.append("hudson.AbortException: Deployment ist nicht bereit geworden\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Snapshots, Evidence und Berichte
    // ------------------------------------------------------------------

    /** Synthetischer Snapshotentwurf mit vollstaendigem Status und ohne Links. */
    static SnapshotDraft draft(List<RawEvidence> evidence, Map<String, Object> normalized) {
        Instant started = Instant.parse("2026-10-01T10:00:00Z");
        return new SnapshotDraft(APP_ID, ENVIRONMENT, DataMode.FIXTURE, new BuildRef(JENKINS_ID, JOB_PATH, 42), true,
                CLUSTER_ID, OC_SERVER, "fixture-context", NAMESPACE, "Deployment", "fixture-app", started,
                started.plusSeconds(45), InvestigationWindow.unknown(started.plusSeconds(45)),
                CollectionStatus.COMPLETE,
                List.of(new SourceSummary(CLUSTER_ID, SourceStatus.COLLECTED, started, List.of(), List.of(),
                        List.of())),
                List.of(), evidence, normalized, Map.of("java", "25", "opsAgent", "abnahme-test"), List.of(),
                List.of());
    }

    /** Evidence mit einfachen Logzeilen. */
    static RawEvidence logEvidence(String id, List<String> lines) {
        return new RawEvidence(id, EvidenceType.KUBERNETES_CONTAINER_LOG, CLUSTER_ID, null, null,
                Locator.of("fixture/pods/fixture-app/log"), lines, null, "Synthetischer Container-Log", List.of());
    }

    /** Evidence mit nummerierten Zeilen; erlaubt Aussagen zu Zeilenverweisen. */
    static List<String> numberedLines(int count, String prefix) {
        List<String> lines = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            lines.add(prefix + " Zeile " + i);
        }
        return List.copyOf(lines);
    }

    /** Gueltiger Berichtsentwurf, gebunden an die uebergebene Snapshot-ID. */
    static DiagnosisDocument validDocument(String snapshotId, String evidenceId, int lastLine) {
        return new DiagnosisDocument(DiagnosisDocument.SCHEMA_VERSION, snapshotId, DiagnosisDocument.LANGUAGE_DE_CH,
                ch.so.agi.ops.domain.AssessmentStatus.PROBABLE_CAUSE,
                "Liveness-Prüfung unterbricht die Startphase",
                "Der Container wird während der Initialisierung neu gestartet.",
                List.of(new DiagnosisDocument.Fact("F-1",
                        "Das erfasste Event nennt einen Neustart nach fehlgeschlagener Liveness-Prüfung.",
                        List.of(new DiagnosisDocument.EvidenceRef(evidenceId, 1, Math.max(1, lastLine))))),
                List.of(new DiagnosisDocument.Hypothesis("H-1", "startup-budget",
                        "Das Startbudget der Liveness-Prüfung ist zu knapp.",
                        DiagnosisDocument.Strength.SUPPORTED, List.of("F-1"), List.of(),
                        List.of("Synthetischer Beispieldatensatz."))),
                List.of(new DiagnosisDocument.TimelineEntry(null, "unknown", "Synthetisches Ereignis.",
                        List.of(new DiagnosisDocument.EvidenceRef(evidenceId, null, null)))),
                List.of(new DiagnosisDocument.Recommendation("Startbudget prüfen.", "Synthetische Begruendung.",
                        List.of("H-1"), "Synthetisches Risiko.", "Synthetische Verifikation.", false)),
                List.of("Welches Startbudget deckt die beobachteten Starts ab?"),
                List.of("Synthetischer Beispieldatensatz; keine Live-Abnahme."), List.of());
    }

    /** Kopie eines Berichtsentwurfs mit ersetztem Titel und ersetzter Zusammenfassung. */
    static DiagnosisDocument withTexts(DiagnosisDocument document, String title, String summary) {
        return new DiagnosisDocument(document.schemaVersion(), document.snapshotId(), document.language(),
                document.assessment(), title, summary, document.facts(), document.hypotheses(), document.timeline(),
                document.recommendations(), document.openQuestions(), document.limitations(),
                document.actionsTaken());
    }

    /** Kopie eines gelesenen Snapshots mit manipulierter Snapshot-ID im Manifest. */
    static StoredSnapshot withManifestSnapshotId(StoredSnapshot snapshot, String snapshotId) {
        ch.so.agi.ops.snapshot.SnapshotManifest manifest = snapshot.manifest();
        ch.so.agi.ops.snapshot.SnapshotManifest manipuliert = new ch.so.agi.ops.snapshot.SnapshotManifest(
                manifest.schemaVersion(), snapshotId, manifest.useCase(), manifest.dataMode(),
                manifest.applicationId(), manifest.environment(), manifest.buildRef(), manifest.clusterId(),
                manifest.namespace(), manifest.workloadKind(), manifest.workloadName(),
                manifest.collectionStartedAt(), manifest.collectionFinishedAt(), manifest.investigationWindow(),
                manifest.collectionStatus(), manifest.sourceResults(), manifest.links(), manifest.evidenceIndex(),
                manifest.redaction(), manifest.toolVersions(), manifest.warnings(), manifest.errors());
        return new StoredSnapshot(manipuliert, snapshot.normalized(), snapshot.rootPath());
    }

    static EvidenceIndexEntry evidenceEntry(StoredSnapshot snapshot, String evidenceId) {
        EvidenceIndexEntry entry = snapshot.evidence(evidenceId);
        assertFalse(entry == null, "Evidence " + evidenceId + " muss im Snapshotindex stehen.");
        return entry;
    }

    // ------------------------------------------------------------------
    // Dateisystem
    // ------------------------------------------------------------------

    static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void writeString(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Alle regulaeren Dateien unterhalb einer Wurzel als relative Pfade mit Dateigroesse. */
    static Map<String, Long> tree(Path root) {
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).forEach(file -> {
                try {
                    result.put(root.relativize(file).toString(), Files.size(file));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return Map.copyOf(result);
    }

    static void assertNoCanary(String text, String where) {
        assertFalse(text != null && text.contains(CANARY),
                where + ": Canary-Wert ist im Klartext erhalten:\n" + text);
    }
}
