package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerLog;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.util.DefaultProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adaptertest des echten {@link OcClusterReader} gegen das kontrollierte {@link FakeOc}.
 *
 * <p>Es wird niemals ein echtes {@code oc} aus dem {@code PATH} ausgefuehrt: Die
 * Clusterdefinition verweist ausdruecklich auf das Fake-Skript. Nach jedem Test prueft
 * {@link FakeOc#assertOnlyAllowedCalls()} die Whitelist der Argumente.</p>
 */
class OcClusterReaderTest {

    private static final String NS = OcFixtures.NAMESPACE;
    private static final String CLUSTER_ID = "fixture-ocp";
    private static final String EMPTY_EVENTS = """
            {"apiVersion": "v1", "kind": "EventList", "items": []}
            """;
    private static final String EMPTY_EVENTS_K8S_IO = """
            {"apiVersion": "events.k8s.io/v1", "kind": "EventList", "items": []}
            """;

    @TempDir
    Path tempDir;

    private FakeOc fakeOc;
    private Path kubeconfig;
    private Path execDirectory;

    @BeforeEach
    void setUp() throws IOException {
        Assumptions.assumeTrue(FakeOc.pythonAvailable(),
                "python3 ist nicht ausfuehrbar; der Fake-oc-Test wird uebersprungen.");
        execDirectory = tempDir.resolve("fake-oc");
        fakeOc = FakeOc.create(execDirectory);
        kubeconfig = writeKubeconfig("kubeconfig.yaml", OcFixtures.KUBECONFIG_VALID);
    }

    @AfterEach
    void nurErlaubteAufrufe() {
        if (fakeOc != null) {
            fakeOc.assertOnlyAllowedCalls();
        }
    }

    // ------------------------------------------------------------------
    // Erfolgsfaelle
    // ------------------------------------------------------------------

    @Test
    void deploymentErfolgsfallErfasstWorkloadRevisionenPodsEventsUndLogs() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, OcFixtures.EVENTS_CORE_JSON)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, OcFixtures.EVENTS_K8S_IO_JSON)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respond(FakeOc.logKey(OcFixtures.POD_A, true), OcFixtures.LOG_PREVIOUS)
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        assertEquals(CLUSTER_ID, result.sourceId());
        assertEquals(OcTestTargets.NOW, result.collectedAt());
        assertEquals(OcFixtures.SERVER, result.data().server());
        assertEquals(OcFixtures.CONTEXT, result.data().context());
        assertEquals(NS, result.data().namespace());
        assertEquals("Deployment", result.data().workloadRequestedKind());

        assertNotNull(result.data().workload());
        assertEquals(OcFixtures.DEPLOYMENT_UID, result.data().workload().uid());
        assertEquals(2, result.data().revisions().size());
        assertEquals(2, result.data().pods().size());
        assertEquals(8, result.data().events().size());
        assertEquals(1, result.data().events().stream().filter(EventSnapshot::isDuplicate).count());
        assertEquals(4, result.data().logs().size());
        assertFalse(result.notes().isEmpty());

        ContainerLog appCurrent = log(result.data(), OcFixtures.POD_A, false);
        assertTrue(appCurrent.available());
        assertFalse(appCurrent.lines().toString().contains(OcFixtures.LOG_CANARY),
                "Logzeilen werden redigiert.");
        assertTrue(appCurrent.lines().toString().contains("[redigiert]"));
        assertNull(appCurrent.sinceTime(), "Ohne bekannte Buildzeit wird kein --since-time gesetzt.");

        ContainerLog appPrevious = log(result.data(), OcFixtures.POD_A, true);
        assertTrue(appPrevious.available());
        assertFalse(appPrevious.lines().isEmpty());

        ContainerLog sidecarPrevious = log(result.data(), OcFixtures.POD_B, true);
        assertFalse(sidecarPrevious.available());
        assertTrue(sidecarPrevious.skipReason().contains("Restartcount 0"),
                "Restartcount 0 ergibt einen Skip mit Begruendung.");
        assertNull(sidecarPrevious.errorCode(), "Restartcount 0 ist ausdruecklich nicht NO_PREVIOUS_LOG.");
        assertTrue(result.errors().stream().noneMatch(error -> error.code() == ErrorCode.NO_PREVIOUS_LOG));

        assertTrue(result.data().events().stream().anyMatch(event -> event.involvedObject() != null
                        && "fixture-pod-uid-old-99".equals(event.involvedObject().uid())),
                "Ein Event zu einer aelteren Podinstanz mit gleichem Namen bleibt als Beleg erhalten (T11).");
        assertTrue(result.data().events().stream().anyMatch(event -> event.bestKnownTime() == null),
                "Fehlende Zeiten bleiben null.");

        assertEquals(List.of(
                FakeOc.KEY_CONTEXT,
                FakeOc.KEY_SERVER,
                FakeOc.KEY_VERSION,
                FakeOc.KEY_GET_DEPLOYMENT,
                FakeOc.KEY_GET_REPLICA_SETS,
                FakeOc.KEY_GET_PODS,
                FakeOc.KEY_GET_EVENTS,
                FakeOc.KEY_GET_EVENTS_K8S_IO,
                FakeOc.logKey(OcFixtures.POD_A, false),
                FakeOc.logKey(OcFixtures.POD_A, true),
                FakeOc.logKey(OcFixtures.POD_B, false)), fakeOc.callKeys());
        assertTrue(fakeOc.callKeys().stream().noneMatch(key -> key.contains("--follow")));
    }

    @Test
    void deploymentConfigFallNutztReplicationControllersUndEigenenSelektor() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT_CONFIG, OcFixtures.DEPLOYMENT_CONFIG_JSON)
                .respond(FakeOc.KEY_GET_REPLICATION_CONTROLLERS, OcFixtures.REPLICATION_CONTROLLERS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respond(FakeOc.logKey(OcFixtures.POD_A, true), OcFixtures.LOG_PREVIOUS)
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentConfigWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        assertEquals("DeploymentConfig", result.data().workload().kind());
        assertEquals("4", result.data().workload().revisionHint());
        assertEquals(1, result.data().revisions().size());
        assertEquals("ReplicationController", result.data().revisions().get(0).kind());
        assertEquals(4, result.data().revisions().get(0).revisionNumber());

        List<String> controllerCall = fakeOc.calls().stream()
                .filter(arguments -> arguments.size() > 1 && "replicationcontrollers".equals(arguments.get(1)))
                .findFirst().orElseThrow();
        assertEquals(List.of("get", "replicationcontrollers", "--kubeconfig", kubeconfig.toString(), "--context",
                OcFixtures.CONTEXT, "-n", NS, "-l",
                "app=fixture-modelfinder,deploymentconfig=fixture-modelfinder", "-o", "json"), controllerCall);
    }

    @Test
    void mitBekannterBuildzeitWirdSinceTimeGesetzt() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respond(FakeOc.logKey(OcFixtures.POD_A, true), OcFixtures.LOG_PREVIOUS)
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.knownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        List<String> logCall = fakeOc.calls().stream()
                .filter(arguments -> "logs".equals(arguments.get(0)) && !arguments.contains("--previous"))
                .findFirst().orElseThrow();
        assertTrue(logCall.contains("--since-time"), logCall.toString());
        assertEquals(OcTestTargets.WINDOW_FROM.toString(), logCall.get(logCall.indexOf("--since-time") + 1));
        assertTrue(result.data().logs().stream().filter(ContainerLog::available)
                .allMatch(log -> OcTestTargets.WINDOW_FROM.equals(log.sinceTime())));
    }

    @Test
    void ohneContainerauswahlWerdenKeineLogsGelesen() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO);

        WorkloadDefinition workload = OcTestTargets.workload(WorkloadDefinition.WorkloadKind.DEPLOYMENT,
                OcFixtures.WORKLOAD, List.of(), false);
        SourceResult<ClusterCollection> result = collect(workload, Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        assertTrue(result.data().logs().isEmpty());
        assertFalse(result.data().pods().isEmpty(), "Containermetadaten werden weiterhin erfasst.");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Keine Containerauswahl")));
        assertTrue(fakeOc.calls().stream().noneMatch(arguments -> "logs".equals(arguments.get(0))));
    }

    // ------------------------------------------------------------------
    // Fehlerfaelle mit strukturiertem Ergebnis
    // ------------------------------------------------------------------

    @Test
    void forbiddenBeimEventLesenErgibtTruncatedUndErhaeltDieUebrigenBelege() throws IOException {
        String forbidden = "Error from server (Forbidden): events is forbidden: User "
                + "\"system:serviceaccount:fixture\" cannot list resource \"events\" in API group \"\"";
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respondFailure(FakeOc.KEY_GET_EVENTS, 1, forbidden)
                .respondFailure(FakeOc.KEY_GET_EVENTS_K8S_IO, 1, forbidden)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respond(FakeOc.logKey(OcFixtures.POD_A, true), OcFixtures.LOG_PREVIOUS)
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.FORBIDDEN));
        assertNotNull(result.data().workload(), "Der Workload bleibt als Beleg erhalten.");
        assertEquals(2, result.data().pods().size());
        assertTrue(result.data().events().isEmpty());
        assertEquals(4, result.data().logs().size(), "Logs werden trotz Eventfehler gelesen.");
        assertTrue(result.data().pods().stream().noneMatch(pod -> pod.name().isBlank()));
    }

    @Test
    void x509FehlerErgibtTlsUntrustedCaUndKeineWeiterenAbfragen() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respondFailure(FakeOc.KEY_GET_DEPLOYMENT, 1,
                        "Unable to connect to the server: x509: certificate signed by unknown authority");

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.UNREACHABLE, result.status());
        assertEquals(ErrorCode.TLS_UNTRUSTED_CA, result.errors().get(0).code());
        assertNull(result.data().workload());
        assertEquals(List.of(FakeOc.KEY_CONTEXT, FakeOc.KEY_SERVER, FakeOc.KEY_VERSION, FakeOc.KEY_GET_DEPLOYMENT),
                fakeOc.callKeys());
    }

    @Test
    void zeitueberschreitungErgibtRequestTimeoutUndBeendetDenProzess() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respondSlow(FakeOc.KEY_GET_PODS, 30, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO);

        long started = System.nanoTime();
        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                limitsWithRequestTimeout(1), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.REQUEST_TIMEOUT),
                result.errors().toString());
        assertTrue(elapsedMillis < 20_000, "Der Timeout greift; Laufzeit war " + elapsedMillis + " ms.");
        assertFalse(fakeOc.processCompleted(FakeOc.KEY_GET_PODS),
                "Der haengende Prozess wurde vor dem Ende beendet.");
        assertNotNull(result.data().workload());
    }

    @Test
    void nichtUnterstuetzterDeploymentConfigTypWirdErklaert() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respondFailure(FakeOc.KEY_GET_DEPLOYMENT_CONFIG, 1,
                        "error: the server doesn't have a resource type \"deploymentconfig\"");

        SourceResult<ClusterCollection> result = collect(deploymentConfigWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ErrorCode.UNSUPPORTED_WORKLOAD, result.errors().get(0).code());
        assertNull(result.data().workload());
    }

    @Test
    void falscherWorkloadTypInDerAntwortErgibtUnsupportedWorkload() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT_CONFIG, OcFixtures.WRONG_KIND_JSON);

        SourceResult<ClusterCollection> result = collect(deploymentConfigWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ErrorCode.UNSUPPORTED_WORKLOAD, result.errors().get(0).code());
    }

    @Test
    void nichtGefundenerWorkloadErgibtNotFoundOhneWeitereAbfragen() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respondFailure(FakeOc.KEY_GET_DEPLOYMENT, 1,
                        "Error from server (NotFound): deployments.apps \"fixture-modelfinder\" not found");

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ErrorCode.NOT_FOUND, result.errors().get(0).code());
        assertEquals(4, fakeOc.calls().size(), "Nach dem Workloadfehler wird nichts mehr abgefragt.");
    }

    @Test
    void budgeterschoepfungErgibtTruncatedMitBudgetfehler() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(4, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.BUDGET_EXCEEDED));
        assertEquals(4, fakeOc.calls().size(), "Nach dem Budget wird kein weiterer Aufruf ausgefuehrt.");
        assertNotNull(result.data().workload());
    }

    @Test
    void abgeschnittenesLogWirdAlsTruncationGemeldet() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respond(FakeOc.logKey(OcFixtures.POD_A, true), OcFixtures.LOG_PREVIOUS)
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                limitsWithBytes(40), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.DATA_TRUNCATED));
        ContainerLog truncated = log(result.data(), OcFixtures.POD_A, false);
        assertTrue(truncated.truncated());
        assertFalse(truncated.truncationNote().isBlank());
        assertTrue(truncated.available(), "Ein abgeschnittenes Log ist kein Fehler und kein leeres Log.");
    }

    @Test
    void fehlendesVorherigesLogErgibtNoPreviousLog() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO)
                .respond(FakeOc.logKey(OcFixtures.POD_A, false), OcFixtures.LOG_CURRENT)
                .respondFailure(FakeOc.logKey(OcFixtures.POD_A, true), 1,
                        "previous terminated container \"fixture-app\" in pod \"fixture-modelfinder\" not found")
                .respond(FakeOc.logKey(OcFixtures.POD_B, false), OcFixtures.LOG_CURRENT);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        ContainerLog previous = log(result.data(), OcFixtures.POD_A, true);
        assertFalse(previous.available(), "Ein Fehler ist kein leeres, erfolgreich gelesenes Log.");
        assertEquals(ErrorCode.NO_PREVIOUS_LOG.name(), previous.errorCode());
        assertTrue(previous.lines().isEmpty());
    }

    // ------------------------------------------------------------------
    // Scope, Sicherheit und Budget
    // ------------------------------------------------------------------

    @Test
    void kubeconfigMitExecPluginWirdAbgelehntOhneOcAufruf() throws IOException {
        Path execKubeconfig = writeKubeconfig("exec.yaml", OcFixtures.KUBECONFIG_EXEC_PLUGIN);

        SourceResult<ClusterCollection> result = collectWithKubeconfig(execKubeconfig);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(result.errors().get(0).message().contains("exec"));
        assertTrue(fakeOc.calls().isEmpty(), "Bei abgelehnter kubeconfig wird kein oc aufgerufen.");
    }

    @Test
    void kubeconfigMitInsecureSkipTlsVerifyWirdAbgelehntOhneOcAufruf() throws IOException {
        Path insecureKubeconfig = writeKubeconfig("insecure.yaml", OcFixtures.KUBECONFIG_INSECURE);

        SourceResult<ClusterCollection> result = collectWithKubeconfig(insecureKubeconfig);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty());
    }

    @Test
    void kubeconfigMitProxyUrlWirdAbgelehntOhneOcAufruf() throws IOException {
        Path proxyKubeconfig = writeKubeconfig("proxy.yaml", OcFixtures.KUBECONFIG_PROXY_URL);

        SourceResult<ClusterCollection> result = collectWithKubeconfig(proxyKubeconfig);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty());
    }

    @Test
    void fehlendeKubeconfigdateiErgibtConfigIncomplete() {
        SourceResult<ClusterCollection> result = collectWithKubeconfig(tempDir.resolve("fehlt.yaml"));

        assertEquals(SourceStatus.NOT_ATTEMPTED, result.status());
        assertEquals(ErrorCode.CONFIG_INCOMPLETE, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty());
    }

    @Test
    void falscherServerImTargetWirdAbgelehntOhneOcAufruf() {
        ClusterDefinition cluster = OcTestTargets.clusterWithExecutable(CLUSTER_ID, fakeOc.executable(), kubeconfig,
                "https://api.andere-adresse.invalid:6443", OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of());

        SourceResult<ClusterCollection> result = collect(cluster, deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty(),
                "Ein abweichender Server wird vor dem ersten oc-Aufruf erkannt (S08).");
    }

    @Test
    void namespaceAusserhalbDerAllowlistErgibtScopeDeniedOhneOcAufruf() {
        ClusterDefinition cluster = OcTestTargets.clusterWithExecutable(CLUSTER_ID, fakeOc.executable(), kubeconfig,
                OcFixtures.SERVER, OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of("fixture-other"));

        SourceResult<ClusterCollection> result = collect(cluster, deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty(), "S01: kein Quellaufruf bei nicht freigegebenem Namespace.");
    }

    @Test
    void shellmetazeichenWerdenAlsArgumentUebergebenUndNichtInterpretiert() throws IOException {
        String canaryName = "fixture-modelfinder; touch ops-agent-shell-canary";
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO);

        WorkloadDefinition workload = OcTestTargets.workload(WorkloadDefinition.WorkloadKind.DEPLOYMENT,
                canaryName, List.of(), false);
        SourceResult<ClusterCollection> result = collect(workload, Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        List<String> deploymentCall = fakeOc.calls().stream()
                .filter(arguments -> arguments.size() > 1 && "deployment".equals(arguments.get(1)))
                .findFirst().orElseThrow();
        assertTrue(deploymentCall.contains(canaryName),
                "Der Name wird als ein Argument uebergeben: " + deploymentCall);
        assertEquals(2, deploymentCall.indexOf(canaryName));
        assertTrue(Files.notExists(Path.of("ops-agent-shell-canary")),
                "Ohne Shell wird nichts interpretiert; es entsteht keine Datei.");
    }

    @Test
    void selbstGebauterProxyEintragWirdZusammengefuehrtUndBestehendesBleibtErhalten() {
        assertEquals("vorhanden.invalid,api.ocp.so.ch",
                OcClusterReader.mergeHosts("vorhanden.invalid", List.of("api.ocp.so.ch")));
        assertEquals("api.ocp.so.ch", OcClusterReader.mergeHosts(null, List.of("api.ocp.so.ch")));
        assertEquals("A.invalid,B.invalid", OcClusterReader.mergeHosts("A.invalid,B.invalid", List.of("b.invalid")),
                "Doppelte Eintraege werden unabhaengig von der Schreibweise nicht wiederholt.");
        assertEquals("", OcClusterReader.mergeHosts(null, List.of()));

        ClusterDefinition cluster = OcTestTargets.clusterWithExecutable(CLUSTER_ID, fakeOc.executable(), kubeconfig,
                OcFixtures.SERVER, OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of());
        Map<String, String> environment = OcClusterReader.proxyEnvironment(cluster);
        assertTrue(environment.get("NO_PROXY").contains("api.ocp.so.ch"));
        assertTrue(environment.get("no_proxy").contains("api.ocp.so.ch"));
        assertTrue(OcClusterReader.proxyEnvironment(OcTestTargets.clusterWithExecutable(CLUSTER_ID,
                fakeOc.executable(), kubeconfig, OcFixtures.SERVER, OcFixtures.CONTEXT, List.of(), List.of()))
                .isEmpty(), "Ohne directHosts wird die Kindumgebung nicht veraendert.");
    }

    @Test
    void kindumgebungErhaeltDieProxyAusnahmeTatsaechlich() throws IOException {
        fakeOc.withSuccessfulPreflight()
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of()), Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        Map<String, String> childEnvironment = fakeOc.childNoProxyEnvironment();
        assertTrue(childEnvironment.getOrDefault("NO_PROXY", "").contains("api.ocp.so.ch"),
                "Die Direkthosts stehen in NO_PROXY der Kindumgebung: " + childEnvironment);
        assertTrue(childEnvironment.getOrDefault("no_proxy", "").contains("api.ocp.so.ch"),
                "Die Direkthosts stehen in no_proxy der Kindumgebung: " + childEnvironment);
    }

    @Test
    void kontextUndVersionWerdenAlsHinweisGemeldet() throws IOException {
        fakeOc.respond(FakeOc.KEY_CONTEXT, "anderer-context\n")
                .respond(FakeOc.KEY_SERVER, OcFixtures.SERVER + "\n")
                .respond(FakeOc.KEY_VERSION, OcFixtures.VERSION_JSON)
                .respond(FakeOc.KEY_GET_DEPLOYMENT, OcFixtures.DEPLOYMENT_JSON)
                .respond(FakeOc.KEY_GET_REPLICA_SETS, OcFixtures.REPLICA_SETS_JSON)
                .respond(FakeOc.KEY_GET_PODS, OcFixtures.PODS_JSON)
                .respond(FakeOc.KEY_GET_EVENTS, EMPTY_EVENTS)
                .respond(FakeOc.KEY_GET_EVENTS_K8S_IO, EMPTY_EVENTS_K8S_IO);

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of()), Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("anderer-context")),
                "Ein abweichender aktiver Context wird als Hinweis genannt.");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("4.14.3")),
                "Die Serverversion wird als Hinweis genannt.");
        assertTrue(result.notes().stream().noneMatch(note -> note.contains(OcFixtures.KUBECONFIG_TOKEN)),
                "Der Tokenwert erscheint in keinem Hinweis.");
        assertTrue(result.errors().stream().noneMatch(error -> error.message().contains(OcFixtures.KUBECONFIG_TOKEN)));
    }

    @Test
    void gemeldeterFremderServerWirdAbgelehnt() throws IOException {
        fakeOc.respond(FakeOc.KEY_CONTEXT, OcFixtures.CONTEXT + "\n")
                .respond(FakeOc.KEY_SERVER, "https://api.fremd.invalid:6443\n");

        SourceResult<ClusterCollection> result = collect(deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)),
                Limits.defaults(), OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertEquals(List.of(FakeOc.KEY_CONTEXT, FakeOc.KEY_SERVER), fakeOc.callKeys(),
                "Bei abweichendem Server wird keine Inhaltsabfrage ausgefuehrt.");
    }

    // ------------------------------------------------------------------
    // Whitelist des Fake-oc
    // ------------------------------------------------------------------

    @Test
    void verboteneArgumenteWerdenErkannt() throws IOException {
        FakeOc geprueft = FakeOc.create(tempDir.resolve("whitelist"));
        geprueft.appendRawCall(List.of("get", "pods", "-o", "json"));
        geprueft.assertOnlyAllowedCalls();

        geprueft.appendRawCall(List.of("delete", "pod", OcFixtures.POD_A));
        assertThrows(AssertionError.class, geprueft::assertOnlyAllowedCalls);

        FakeOc follow = FakeOc.create(tempDir.resolve("whitelist-follow"));
        follow.appendRawCall(List.of("logs", OcFixtures.POD_A, "--follow"));
        assertThrows(AssertionError.class, follow::assertOnlyAllowedCalls);

        FakeOc exec = FakeOc.create(tempDir.resolve("whitelist-exec"));
        exec.appendRawCall(List.of("exec", OcFixtures.POD_A, "--", "sh"));
        assertThrows(AssertionError.class, exec::assertOnlyAllowedCalls);

        FakeOc useContext = FakeOc.create(tempDir.resolve("whitelist-context"));
        useContext.appendRawCall(List.of("config", "use-context", "anderer"));
        assertThrows(AssertionError.class, useContext::assertOnlyAllowedCalls);

        FakeOc apply = FakeOc.create(tempDir.resolve("whitelist-apply"));
        apply.appendRawCall(List.of("apply", "-f", "fixture.yaml"));
        assertThrows(AssertionError.class, apply::assertOnlyAllowedCalls);
    }

    @Test
    void fixturemodusLoestKeinenLiveZugriffAus() {
        EnvironmentDefinition environment = OcTestTargets.environment(CLUSTER_ID, NS,
                deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)));
        ClusterDefinition cluster = defaultCluster();
        OpsConfig config = OcTestTargets.config(cluster, Limits.defaults(), environment);
        CollectionBudget budget = OcTestTargets.budget(60, 8, 500);
        ResolvedTarget liveTarget = OcTestTargets.target(config, environment, budget);
        ResolvedTarget fixtureTarget = new ResolvedTarget(liveTarget.targetId(), config,
                ch.so.agi.ops.domain.DataMode.FIXTURE, liveTarget.applicationId(), liveTarget.environment(),
                environment, liveTarget.buildRef(), null, liveTarget.resolvedAt(), liveTarget.expiresAt(), budget,
                List.of(), "fixture-scenario");
        OcClusterReader reader = new OcClusterReader(config, DefaultProcessRunner.inheritingEnvironment(),
                Clock.fixed(OcTestTargets.NOW, ZoneOffset.UTC), YamlSupport.jsonMapper(), OcTestTargets.unknownWindow(),
                CLUSTER_ID);

        SourceResult<ClusterCollection> result = reader.collect(fixtureTarget, budget);

        assertEquals(SourceStatus.NOT_ATTEMPTED, result.status());
        assertEquals(ErrorCode.CONFIG_INVALID, result.errors().get(0).code());
        assertTrue(fakeOc.calls().isEmpty(), "Im Fixturemodus wird niemals ein oc gestartet.");
    }

    @Test
    void unerwarteteAufrufeLassenDenTestScheitern() throws IOException {
        FakeOc leer = FakeOc.create(tempDir.resolve("unerwartet"));
        leer.respond(FakeOc.KEY_CONTEXT, OcFixtures.CONTEXT + "\n");

        SourceResult<ClusterCollection> result = collect(leer, deploymentWorkload(List.of()), Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());

        assertEquals(SourceStatus.UNREACHABLE, result.status(),
                "Ein nicht hinterlegter Aufruf ist ein technischer Fehler, kein leeres Ergebnis.");
        assertTrue(leer.hasUnexpectedCalls());
        assertThrows(AssertionError.class, leer::assertOnlyAllowedCalls);
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private WorkloadDefinition deploymentWorkload(List<String> containers) {
        return OcTestTargets.workload(WorkloadDefinition.WorkloadKind.DEPLOYMENT, OcFixtures.WORKLOAD, containers,
                false);
    }

    private WorkloadDefinition deploymentConfigWorkload(List<String> containers) {
        return OcTestTargets.workload(WorkloadDefinition.WorkloadKind.DEPLOYMENT_CONFIG, OcFixtures.WORKLOAD,
                containers, false);
    }

    private ClusterDefinition defaultCluster() {
        return OcTestTargets.clusterWithExecutable(CLUSTER_ID, fakeOc.executable(), kubeconfig, OcFixtures.SERVER,
                OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of());
    }

    private SourceResult<ClusterCollection> collect(WorkloadDefinition workload, Limits limits,
            CollectionBudget budget, Function<ResolvedTarget, InvestigationWindow> window) {
        return collect(defaultCluster(), workload, limits, budget, window);
    }

    private SourceResult<ClusterCollection> collect(ClusterDefinition cluster, WorkloadDefinition workload,
            Limits limits, CollectionBudget budget, Function<ResolvedTarget, InvestigationWindow> window) {
        EnvironmentDefinition environment = OcTestTargets.environment(cluster.id(), NS, workload);
        OpsConfig config = OcTestTargets.config(cluster, limits, environment);
        ResolvedTarget target = OcTestTargets.target(config, environment, budget);
        OcClusterReader reader = new OcClusterReader(config, DefaultProcessRunner.inheritingEnvironment(),
                Clock.fixed(OcTestTargets.NOW, ZoneOffset.UTC), YamlSupport.jsonMapper(), window, cluster.id());
        return reader.collect(target, budget);
    }

    private SourceResult<ClusterCollection> collect(FakeOc other, WorkloadDefinition workload, Limits limits,
            CollectionBudget budget, Function<ResolvedTarget, InvestigationWindow> window) {
        ClusterDefinition cluster = OcTestTargets.clusterWithExecutable(CLUSTER_ID, other.executable(), kubeconfig,
                OcFixtures.SERVER, OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of());
        return collect(cluster, workload, limits, budget, window);
    }

    private SourceResult<ClusterCollection> collectWithKubeconfig(Path kubeconfigPath) {
        ClusterDefinition cluster = OcTestTargets.clusterWithExecutable(CLUSTER_ID, fakeOc.executable(),
                kubeconfigPath, OcFixtures.SERVER, OcFixtures.CONTEXT, List.of("api.ocp.so.ch"), List.of());
        return collect(cluster, deploymentWorkload(List.of(OcFixtures.CONTAINER_MAIN)), Limits.defaults(),
                OcTestTargets.budget(60, 8, 500), OcTestTargets.unknownWindow());
    }

    private static Limits limitsWithRequestTimeout(int seconds) {
        return new Limits(90, seconds, 5, 3, 60, 8, 500, 12, 8L * 1024 * 1024, 1024L * 1024, 32L * 1024 * 1024,
                32 * 1024, 256 * 1024);
    }

    private static Limits limitsWithBytes(long maxContainerLogBytes) {
        return new Limits(90, 15, 5, 3, 60, 8, 500, 12, 8L * 1024 * 1024, maxContainerLogBytes,
                32L * 1024 * 1024, 32 * 1024, 256 * 1024);
    }

    private Path writeKubeconfig(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static ContainerLog log(ClusterCollection collection, String podName, boolean previous) {
        return collection.logs().stream()
                .filter(log -> log.podName().equals(podName) && log.previous() == previous)
                .findFirst().orElseThrow(() -> new AssertionError("Kein Log fuer " + podName + " previous="
                        + previous + " in " + collection.logs()));
    }
}
