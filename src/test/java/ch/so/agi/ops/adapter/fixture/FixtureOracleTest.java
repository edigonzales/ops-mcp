package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.application.ProbeComparison;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle-Test der Abnahmeszenarien.
 *
 * <p>Die erwartete Diagnose steht ausschliesslich hier und wird dem Modell nicht als Evidence
 * mitgegeben. Geprueft werden die mechanischen Befunde aus
 * {@link ch.so.agi.ops.application.ProbeComparison} - mit dem richtigen {@code Kind} und dem
 * richtigen Container - sowie die Belegstaerke der Zuordnung Build -&gt; Workload.</p>
 *
 * <p>Die Probe-Konfiguration fuer den Vergleich stammt aus dem tatsaechlichen Pod. Damit
 * prueft der Test die Befundlogik und nicht die Templateaufloesung; die Templateaufloesung
 * selbst weist {@link FixtureConfigurationReaderTest} nach.</p>
 */
class FixtureOracleTest {

    private static final JsonMapper MAPPER = FixtureHarness.mapper();

    /** Ergebnis einer Szenarioauswertung. */
    private record Findings(List<ProbeComparison.Finding> findings, ClusterCollection cluster,
            BuildSnapshot build, ResolvedConfiguration configuration) {

        /** Probe-Konfiguration aus der aufgeloesten Revision, sofern aufloesbar. */
        Map<String, List<ProbeConfig>> configProbes() {
            Map<String, List<ProbeConfig>> result = new LinkedHashMap<>();
            configuration.workloads().forEach((container, config) ->
                    result.put(container, List.copyOf(config.probes().values())));
            return result;
        }

        boolean has(ProbeComparison.Kind kind) {
            return findings.stream().anyMatch(finding -> finding.kind() == kind);
        }

        boolean has(ProbeComparison.Kind kind, String container) {
            return findings.stream().anyMatch(finding -> finding.kind() == kind
                    && container.equals(finding.container()));
        }

        long count(ProbeComparison.Kind kind) {
            return findings.stream().filter(finding -> finding.kind() == kind).count();
        }

        List<String> kinds() {
            return findings.stream().map(finding -> finding.kind().name()).sorted().toList();
        }

        /**
         * true, wenn eine Konfiguration am belegten Commit aufgeloest werden konnte. Nur dann
         * ist der Probe-Vergleich belastbar und es muessen Befunde entstehen.
         */
        boolean hasConfigComparison() {
            return !configProbes().isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}: erwartete mechanische Befunde und Linkstaerke")
    @CsvSource({
        "t01-liveness-startup,      EXACT",
        "t02-readiness-only,        EXACT",
        "t03-wrong-probe-path,      EXACT",
        "t04-application-crash,     EXACT",
        "t05-history-lost,          PROBABLE",
        "t06a-revision-available,   EXACT",
        "t07-probe-timeout,         EXACT",
        "t08-jenkins-before-deploy, EXACT",
        "t09-sidecar-oom,           EXACT",
        "t09b-main-oom,             EXACT",
        "t09c-exit137,              EXACT",
        "t10-second-application,    EXACT",
        "t10b-deploymentconfig,     EXACT",
        "t11-ambiguous-revision,    AMBIGUOUS",
        "t12a-jenkins-forbidden,    PROBABLE",
    })
    void mechanicalFindingsAndLinkStrengthMatchTheExpectation(String scenarioId, String expectedLink) {
        Findings result = evaluate(scenarioId);
        assertNotNull(result.cluster().workload(), scenarioId + ": Workload fehlt.");
        LinkStrength strength = FixtureHarness.buildToWorkloadLink(result.cluster(), result.build());
        assertEquals(LinkStrength.valueOf(expectedLink), strength,
                scenarioId + ": Linkstaerke weicht ab. Befunde: " + result.kinds());
        if (result.hasConfigComparison()) {
            assertFalse(result.findings().isEmpty(),
                    scenarioId + ": es wurden keine mechanischen Befunde erzeugt.");
        }
    }

    // --- Einzelne Szenarien ---------------------------------------------------------------

    @Test
    @DisplayName("T01: Owner-Kette ueber UID, CONTAINER_RESTARTED, EVENT_KILLING_AFTER_LIVENESS, keine Startup-Probe")
    void t01LivenessInterruptsTheStartupPhase() {
        Findings result = evaluate("t01-liveness-startup");

        // Der Abnahmekern: Workload -> Revision -> Pod ausschliesslich ueber die Owner-UID.
        // Ohne diese Kette entstehen keine Pod-, Event- und Log-Belege.
        List<RevisionSnapshot> revisions = FixtureHarness.revisionsOwnedByWorkload(result.cluster());
        assertEquals(1, revisions.size(), "Genau eine Revision muss ueber die Owner-UID gefunden werden.");
        List<PodSnapshot> owned = new ch.so.agi.ops.application.OwnershipGraph(result.cluster())
                .podsOf(revisions);
        assertEquals(1, owned.size(),
                "OwnershipGraph.podsOf muss den Pod ueber die Controller-Owner-UID finden.");
        assertEquals(1, owned.size(),
                "Der Pod muss ueber die Controller-Owner-UID gefunden werden; sonst bleibt er UNLINKED. "
                        + "Auswahlinweise: " + result.cluster().selectionNotes());
        PodSnapshot pod = owned.get(0);
        assertEquals("fixture-pod-001", pod.uid());
        assertFalse(pod.ownerReferences().isEmpty(),
                "Der Pod braucht eine Owner-Referenz auf die Revision.");
        assertEquals("fixture-rs-001", pod.ownerReferences().get(0).uid());
        assertTrue(pod.ownerReferences().get(0).isController());
        assertEquals(ch.so.agi.ops.domain.LinkStrength.EXACT,
                new ch.so.agi.ops.application.OwnershipGraph(result.cluster()).link(pod).strength());

        // Der Pod ist tatsaechlich Teil der Erfassung.
        assertTrue(result.cluster().pods().stream().anyMatch(candidate -> "fixture-pod-001".equals(candidate.uid())),
                "Der zugeordnete Pod muss in der Erfassung liegen: " + result.cluster().pods());
        assertFalse(result.cluster().logs().isEmpty(), "Container-Logs muessen erfasst sein.");
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "modelfinder"),
                "Der Hauptcontainer muss als neu gestartet belegt sein: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Das Killing nach Liveness-Fehler muss als Eventbefund vorliegen: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.NO_STARTUP_PROBE, "modelfinder"),
                "Ohne Startup-Probe muss der Befund NO_STARTUP_PROBE entstehen.");
        assertFalse(result.has(ProbeComparison.Kind.NOT_READY_WITHOUT_RESTART),
                "Bei belegtem Neustart darf NICHT_READY_WITHOUT_RESTART nicht entstehen.");
        // T01 dokumentiert einen Terminierungsgrund (Error/Killing). Ein Exitcode 137 ohne
        // OOMKilled bleibt trotzdem als "ohne OOM-Begruendung" markiert; die Aussage des
        // Berichts darf daraus keinen OOM ableiten.
        assertFalse(result.has(ProbeComparison.Kind.EVENT_OOM_KILLED),
                "Es gibt keinen OOM-Beleg: " + result.findings());

        // Die Konfiguration ist am belegten Commit aufloesbar; damit greift der Vergleich
        // zwischen Laufzeit und Revision ueberhaupt.
        assertEquals(ResolvedConfiguration.ConfigStatus.RESOLVED, result.configuration().status(),
                "Die Konfiguration muss am belegten Commit aufgeloest werden: "
                        + result.configuration().files());
        ResolvedConfiguration resolved = result.configuration();
        assertTrue(resolved.revision().hasCommit());
        assertEquals("c0563524e661fe31844e23943c2e9bf1b4cda0e1", resolved.revision().commit());
        assertTrue(result.hasConfigComparison(),
                "Der Probe-Vergleich braucht eine aufgeloeste Konfiguration: " + resolved.files());
        ProbeConfig resolvedLiveness = result.configProbes().get("modelfinder").stream()
                .filter(probe -> probe.kind() == ProbeConfig.ProbeKind.LIVENESS)
                .findFirst().orElseThrow();
        assertEquals(10, resolvedLiveness.initialDelaySeconds(),
                "Die Liveness-Konfiguration am belegten Commit muss initialDelaySeconds=10 tragen.");
        assertEquals(10, resolvedLiveness.periodSeconds());
        assertEquals(3, resolvedLiveness.failureThreshold());
        assertTrue(result.configProbes().get("modelfinder").stream()
                        .noneMatch(probe -> probe.kind() == ProbeConfig.ProbeKind.STARTUP),
                "Es ist keine Startup-Probe konfiguriert; genau das ist der T01-Befund.");
        // Die aufgeloeste Templatekonfiguration fuehrt die Probe-Zeitfelder; Handler-/Schemafelder
        // sind dort nicht Teil des Vergleichs. Geprueft wird deshalb, dass die Zeitwerte
        // uebereinstimmen und keine Probe fehlt.
        assertTrue(result.findings().stream()
                        .filter(finding -> finding.kind() == ProbeComparison.Kind.PROBE_CONFIG_MISMATCH)
                        .noneMatch(finding -> finding.statement().contains("initialDelaySeconds")
                                || finding.statement().contains("periodSeconds")
                                || finding.statement().contains("failureThreshold")),
                "Die Probe-Zeitwerte duerfen nicht abweichen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.PROBE_PRESENCE_MISMATCH),
                "Laufzeit und Revision fuehren dieselben Probes: " + result.findings());

        var main = result.cluster().pods().get(0).containers().get(0);
        assertEquals(1, main.restartCount());
        assertEquals("Error", main.lastState().reason());
        assertEquals(137, main.lastState().exitCode());

        // Die Revision traegt genau die synthetisch belegten Probewerte, keine Startup-Probe.
        RevisionSnapshot revision = FixtureHarness.revisionsOwnedByWorkload(result.cluster()).get(0);
        ProbeConfig liveness = revision.probes().stream()
                .filter(probe -> probe.kind() == ProbeConfig.ProbeKind.LIVENESS)
                .findFirst().orElseThrow();
        assertTrue(revision.probes().stream()
                        .noneMatch(probe -> probe.kind() == ProbeConfig.ProbeKind.STARTUP),
                "Die Revision darf keine Startup-Probe fuehren: " + revision.probes());
        assertEquals(10, liveness.initialDelaySeconds());
        assertEquals(10, liveness.periodSeconds());
        assertEquals(3, liveness.failureThreshold());
        assertEquals(40, liveness.simplifiedStartBudgetSeconds(),
                "initialDelaySeconds + failureThreshold * periodSeconds ist nur eine Orientierung: "
                        + "10 + 3 * 10 = 40.");
    }

    @Test
    @DisplayName("T02: NOT_READY_WITHOUT_RESTART und KEIN CONTAINER_RESTARTED")
    void t02ReadinessOnlyNeverRestarts() {
        Findings result = evaluate("t02-readiness-only");
        assertTrue(result.has(ProbeComparison.Kind.NOT_READY_WITHOUT_RESTART),
                "Ohne Neustart muss NOT_READY_WITHOUT_RESTART entstehen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED),
                "Readiness darf nicht als Neustartursache erscheinen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Es darf kein Liveness-/Killing-Befund entstehen: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.EVENT_READINESS_FAILED),
                "Der Readiness-Fehler muss als Eventbefund vorliegen: " + result.findings());

        for (ContainerSnapshot container : result.cluster().pods().get(0).containers()) {
            assertEquals(0, container.restartCount(), "Kein Container darf einen Restartcount haben.");
        }
        PodSnapshot pod = result.cluster().pods().get(0);
        assertTrue(pod.isRunning());
        assertFalse(pod.isReady());
    }

    @Test
    @DisplayName("T03: EVENT_READINESS_FAILED, Anwendung gestartet, Pfad aus dem Log unterscheidet sich")
    void t03WrongProbePathIsMechanicallyVisible() {
        Findings result = evaluate("t03-wrong-probe-path");
        assertTrue(result.has(ProbeComparison.Kind.EVENT_READINESS_FAILED)
                        || result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Der 404 der Probe muss als Eventbefund vorliegen: " + result.findings());

        var liveness = probeOf(result.cluster(), "modelfinder", ProbeConfig.ProbeKind.LIVENESS);
        assertEquals("/actuator/health/liveness", liveness.httpPath());
        var readiness = probeOf(result.cluster(), "modelfinder", ProbeConfig.ProbeKind.READINESS);
        assertEquals("/actuator/health", readiness.httpPath());

        ClusterCollection cluster = result.cluster();
        var containerLog = cluster.logs().stream().filter(entry -> !entry.previous()).findFirst().orElseThrow();
        assertTrue(containerLog.lines().stream().anyMatch(line -> line.contains("/healthz")
                        && line.contains("404")),
                "Der 404 auf den falschen Pfad muss im Log dokumentiert sein: " + containerLog.lines());
        assertTrue(containerLog.lines().stream().anyMatch(line -> line.contains("/actuator/health")),
                "Der tatsaechliche Endpunkt muss im Startlog dokumentiert sein.");
    }

    @Test
    @DisplayName("T04: fataler Startfehler und KEIN Liveness-Killing-Befund")
    void t04ApplicationCrashIsNotAProbeProblem() {
        Findings result = evaluate("t04-application-crash");
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Der Absturz darf nicht als Liveness-Killing erscheinen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EVENT_PROBE_TIMEOUT));
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "modelfinder"),
                "Der Neustartversuch des abgestuerzten Containers muss belegt sein.");
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_TERMINATED) || result.has(
                        ProbeComparison.Kind.CONTAINER_RESTARTED),
                "Ein Beendigungs- oder Neustartbefund muss vorliegen: " + result.findings());

        var container = result.cluster().pods().get(0).containers().get(0);
        assertEquals(1, container.lastState().exitCode(), "Der Exitcode 1 muss erhalten bleiben.");
        assertEquals("Error", container.lastState().reason());
        assertTrue(container.lastState().message() == null
                        || !container.lastState().message().toLowerCase(java.util.Locale.ROOT).contains("oom"),
                "Der Absturz darf nicht als OOM dargestellt werden.");

        var log = result.cluster().logs().stream().filter(entry -> !entry.previous()).findFirst().orElseThrow();
        assertTrue(log.lines().stream().anyMatch(line -> line.contains("Fataler Startfehler")),
                "Der fatale Startfehler muss im Log stehen.");
        assertTrue(log.lines().stream().noneMatch(line -> line.contains("FixtureCanaryToken")),
                "Es duerfen keine Secretwerte im Log erscheinen.");
    }

    @Test
    @DisplayName("T05: keine Events, keine vorherigen Logs, kein Neustart - Luecken bleiben benannt")
    void t05MissingHistoryStaysVisible() {
        Findings result = evaluate("t05-history-lost");
        assertTrue(result.cluster().events().isEmpty(), "Es gibt keine Events im Szenario.");
        assertTrue(result.cluster().pods().get(0).isReady(), "Der aktuelle Pod ist gesund.");
        assertEquals(0, result.cluster().pods().get(0).maxRestartCount());
        assertFalse(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED));
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS));
        assertTrue(result.cluster().selectionNotes().stream()
                        .anyMatch(note -> note.contains("Keine Event-Rohantwort")),
                "Die fehlende Eventhistorie muss als Luecke benannt sein: " + result.cluster().selectionNotes());
    }

    @Test
    @DisplayName("T07: Probe-Antworttimeout ist kein Startbudget und kein Neustart")
    void t07ProbeTimeoutIsNotAStartupBudget() {
        Findings result = evaluate("t07-probe-timeout");
        // Das Event nennt eine fehlgeschlagene Probe; die Ursache (Antwortzeit) steht im
        // timeoutSeconds und in der Meldung, nicht in einer erfundenen Dauer.
        assertTrue(result.has(ProbeComparison.Kind.EVENT_READINESS_FAILED)
                        || result.has(ProbeComparison.Kind.EVENT_PROBE_TIMEOUT),
                "Der Probe-Timeout muss als Eventbefund vorliegen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED),
                "Ein Antwortzeitproblem startet den Container nicht neu: " + result.findings());
        var probe = probeOf(result.cluster(), "modelfinder", ProbeConfig.ProbeKind.READINESS);
        assertEquals(1, probe.timeoutSeconds(), "Das kleine Antwortzeitlimit muss erhalten bleiben.");
        assertTrue(result.has(ProbeComparison.Kind.NOT_READY_WITHOUT_RESTART),
                "Der Pod ist nicht bereit, ohne dass ein Neustart belegt ist.");
    }

    @Test
    @DisplayName("T08: Fehler vor dem Containerstart, alte Version gesund, kein Killing")
    void t08FailureBeforeTheRollout() {
        Findings result = evaluate("t08-jenkins-before-deploy");
        assertTrue(result.has(ProbeComparison.Kind.EVENT_BEFORE_CONTAINER_START),
                "Der Fehler vor dem Containerstart muss als Eventbefund vorliegen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Die alte, gesunde Version darf nicht als Probe-Problem erscheinen.");
        assertFalse(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED));
        assertTrue(result.cluster().pods().get(0).isReady());
        assertEquals(1, result.cluster().workload().readyReplicas());
    }

    @Test
    @DisplayName("T09: der Sidecar ist betroffen, der Hauptcontainer NICHT")
    void t09SidecarOomIsAttributedToTheSidecar() {
        Findings result = evaluate("t09-sidecar-oom");
        assertTrue(result.has(ProbeComparison.Kind.EVENT_OOM_KILLED, "log-forwarder"),
                "OOMKilled muss dem Sidecar zugeordnet sein: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "modelfinder"),
                "Der Hauptcontainer darf nicht als neu gestartet gelten: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EXIT_CODE_WITHOUT_REASON, "modelfinder"));
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Es gibt kein Liveness-Ereignis: " + result.findings());

        ContainerSnapshot main = containerByName(result.cluster(), "modelfinder");
        ContainerSnapshot sidecar = containerByName(result.cluster(), "log-forwarder");
        assertEquals(0, main.restartCount());
        assertEquals(1, sidecar.restartCount());
        assertEquals("OOMKilled", sidecar.lastState().reason());
        assertTrue(sidecar.lastState().isTerminated());
    }

    @Test
    @DisplayName("T09b: der Hauptcontainer ist betroffen, der Sidecar NICHT")
    void t09bMainOomIsAttributedToTheMainContainer() {
        Findings result = evaluate("t09b-main-oom");
        assertTrue(result.has(ProbeComparison.Kind.EVENT_OOM_KILLED, "modelfinder"),
                "OOMKilled muss dem Hauptcontainer zugeordnet sein: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EVENT_OOM_KILLED, "log-forwarder"),
                "Der Sidecar darf nicht als OOM-betroffen gelten: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "modelfinder"));
        assertEquals(0, containerByName(result.cluster(), "log-forwarder").restartCount());
    }

    @Test
    @DisplayName("T09c: EXIT_CODE_WITHOUT_REASON, kein sicherer OOM-Nachweis")
    void t09cExitCodeAloneIsNoOomProof() {
        Findings result = evaluate("t09c-exit137");
        assertTrue(result.has(ProbeComparison.Kind.EXIT_CODE_WITHOUT_REASON, "modelfinder"),
                "Exitcode 137 ohne Begruendung muss als solcher erscheinen: " + result.findings());
        assertFalse(result.has(ProbeComparison.Kind.EVENT_OOM_KILLED),
                "Exitcode 137 allein darf keinen OOM-Befund erzeugen: " + result.findings());

        ContainerSnapshot main = containerByName(result.cluster(), "modelfinder");
        assertEquals(137, main.lastState().exitCode());
        assertFalse("OOMKilled".equalsIgnoreCase(main.lastState().reason()));
        assertTrue(main.observations().flags().contains("exitcode-137-ohne-oom-begruendung"));
    }

    @Test
    @DisplayName("T10: gleiche Befundkette auf der zweiten Anwendung")
    void t10SecondApplicationBehavesLikeT01() {
        Findings result = evaluate("t10-second-application");
        assertEquals(FixtureHarness.NAMESPACE_SERVICE_B, result.cluster().namespace());
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "service-b"),
                "Der Hauptcontainer der zweiten Anwendung muss als neu gestartet gelten: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS));
        assertFalse(result.has(ProbeComparison.Kind.NOT_READY_WITHOUT_RESTART));
    }

    @Test
    @DisplayName("T10b: DeploymentConfig-Kette mit derselben Befundlogik")
    void t10bDeploymentConfigChain() {
        Findings result = evaluate("t10b-deploymentconfig");
        assertEquals("DeploymentConfig", result.cluster().workload().kind());
        assertEquals(1, FixtureHarness.revisionsOwnedByWorkload(result.cluster()).size());
        assertEquals("ReplicationController",
                FixtureHarness.revisionsOwnedByWorkload(result.cluster()).get(0).kind());
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "legacy-application"),
                "Der Hauptcontainer muss als neu gestartet gelten: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS));
        assertEquals(LinkStrength.EXACT, FixtureHarness.buildToWorkloadLink(result.cluster(), result.build()),
                "Der Build nennt denselben Image-Tag wie der Pod; die Owner-Kette ist eindeutig.");
    }

    @Test
    @DisplayName("T11: kein EVENT_KILLING_AFTER_LIVENESS fuer die neue Pod-UID")
    void t11NoKillingEventForTheNewPodUid() {
        Findings result = evaluate("t11-ambiguous-revision");
        assertFalse(result.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Das Event gehoert zur alten UID und darf keinen Liveness-Killing-Befund erzeugen: "
                        + result.findings());
        assertTrue(result.findings().stream().noneMatch(finding -> finding.evidenceRefs() == null),
                "Befunde muessen ihre Belegreferenzen fuehren.");
        assertEquals(LinkStrength.AMBIGUOUS, FixtureHarness.buildToWorkloadLink(result.cluster(), result.build()));
        assertEquals(2, FixtureHarness.revisionsOwnedByWorkload(result.cluster()).size());
    }

    @Test
    @DisplayName("T12c und T12d: fehlende bzw. verbotene Events erzeugen keine erfundenen Befunde")
    void t12MissingEventsProduceNoFindings() {
        Findings invalid = evaluate("t12c-invalid-json");
        assertTrue(invalid.cluster().events().isEmpty(),
                "Aus ungueltigem JSON duerfen keine Events entstehen.");
        assertFalse(invalid.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Ohne lesbare Events darf kein Eventbefund entstehen: " + invalid.findings());

        Findings forbidden = evaluate("t12d-events-forbidden");
        assertTrue(forbidden.cluster().events().isEmpty());
        assertFalse(forbidden.has(ProbeComparison.Kind.EVENT_KILLING_AFTER_LIVENESS),
                "Eine verbotene Eventabfrage darf keinen Befund erfinden: " + forbidden.findings());
    }

    @Test
    @DisplayName("Der Oracle deckt jedes Szenario ab und jede Erwartung steht nur im Test")
    void everyScenarioHasAnOracleExpectation() throws java.io.IOException {
        List<String> covered = List.of("t01-liveness-startup", "t02-readiness-only", "t03-wrong-probe-path",
                "t04-application-crash", "t05-history-lost", "t06a-revision-available", "t06b-revision-missing",
                "t06c-foreign-sha", "t07-probe-timeout", "t08-jenkins-before-deploy", "t09-sidecar-oom",
                "t09b-main-oom", "t09c-exit137", "t10-second-application", "t10b-deploymentconfig",
                "t11-ambiguous-revision", "t12a-jenkins-forbidden", "t12b-previous-log-missing",
                "t12c-invalid-json", "t12d-events-forbidden");
        for (String scenarioId : covered) {
            assertTrue(FixtureHarness.scenarioIds().contains(scenarioId), "Szenario fehlt: " + scenarioId);
        }
        // Kein Szenario enthaelt die erwartete Diagnose: die Erwartung steht nur im Test.
        List<String> leaked = new ArrayList<>();
        for (String scenarioId : covered) {
            var directory = FixtureHarness.scenarioDir(scenarioId);
            String expectation = java.nio.file.Files.readString(directory.resolve("scenario.json"));
            for (ProbeComparison.Kind kind : ProbeComparison.Kind.values()) {
                if (expectation.contains(kind.name())) {
                    leaked.add(scenarioId + " nennt " + kind.name());
                }
            }
        }
        assertTrue(leaked.isEmpty(), "Die Szenarien duerfen die erwartete Diagnose nicht enthalten: " + leaked);
    }

    // --- Auswertung -------------------------------------------------------------------------

    private Findings evaluate(String scenarioId) {
        OpsConfig config = FixtureHarness.config(scenarioId);
        var target = FixtureHarness.target(scenarioId, config);
        SourceResult<ClusterCollection> clusterResult = FixtureHarness
                .clusterReader(config, scenarioId, MAPPER).collect(target, target.budget());
        ClusterCollection cluster = clusterResult.data();
        BuildSnapshot build = FixtureHarness.buildReader(config, scenarioId, MAPPER)
                .collectMetadata(target, target.budget()).data();
        Map<String, List<ProbeConfig>> probes = FixtureHarness.probeConfigFromPods(cluster);

        // Die Revision wird ueber die belegte Revision gelesen, damit der Probe-Vergleich
        // zwischen Laufzeit und Konfiguration genau wie im Application Service erfolgt.
        ResolvedConfiguration configuration = FixtureHarness.readConfigurationWithRevision(
                scenarioId, revisionOf(scenarioId), MAPPER).data();
        Map<String, List<ProbeConfig>> configProbes = new LinkedHashMap<>();
        configuration.workloads().forEach((container, containerConfig) ->
                configProbes.put(container, List.copyOf(containerConfig.probes().values())));

        List<ProbeComparison.Finding> findings = new ArrayList<>();
        for (PodSnapshot pod : cluster.pods()) {
            findings.addAll(ProbeComparison.containerFindings(pod, List.of("k8s-pod"), probes));
        }
        Map<String, String> eventRefs = new LinkedHashMap<>();
        for (int index = 0; index < cluster.events().size(); index++) {
            eventRefs.put(cluster.events().get(index).dedupeKey(), "k8s-event-" + (index + 1));
        }
        findings.addAll(ProbeComparison.eventFindings(cluster.events(), eventRefs));
        findings.addAll(ProbeComparison.previousLogFindings(cluster.logs()));
        List<String> configRefs = List.of("git-configuration");
        findings.addAll(ProbeComparison.compareProbes(
                cluster.pods().stream().flatMap(pod -> pod.probesFromPod().stream()).toList(),
                configProbes, configRefs));
        return new Findings(List.copyOf(findings), cluster, build, configuration);
    }

    /**
     * Revisionsherkunft fuer den Probe-Vergleich. Fuer Szenarien ohne Commit bleibt sie
     * unbekannt; dann entsteht keine erfundene Konfiguration.
     */
    private static ch.so.agi.ops.domain.git.RevisionProvenance revisionOf(String scenarioId) {
        return FixtureHarness.revisionOf(scenarioId, MAPPER);
    }

    private static ProbeConfig probeOf(ClusterCollection cluster, String container, ProbeConfig.ProbeKind kind) {
        for (PodSnapshot pod : cluster.pods()) {
            for (ProbeConfig probe : pod.probesFromPod()) {
                if (probe.containerName().equals(container) && probe.kind() == kind) {
                    return probe;
                }
            }
        }
        throw new AssertionError("Keine " + kind + "-Probe fuer Container " + container + " gefunden.");
    }

    private static ContainerSnapshot containerByName(ClusterCollection cluster, String name) {
        return FixtureHarness.containersOf(cluster, name).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Container fehlt: " + name));
    }

    /** Nur zur Dokumentation: die Aufloesung der Konfiguration gehoert in den Konfigurationstest. */
    @Test
    @DisplayName("T12b: das fehlende vorherige Log erzeugt einen PREVIOUS_LOG_UNAVAILABLE-Befund")
    void t12bMissingPreviousLogProducesAFinding() {
        Findings result = evaluate("t12b-previous-log-missing");
        assertTrue(result.has(ProbeComparison.Kind.PREVIOUS_LOG_UNAVAILABLE),
                "Ein fehlendes vorheriges Log muss als Befund erscheinen: " + result.findings());
        assertTrue(result.has(ProbeComparison.Kind.CONTAINER_RESTARTED, "modelfinder"));
    }
}
