package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.application.OwnershipGraph;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerLog;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft den Fixtureadapter der Clusterquelle.
 *
 * <p>Der Adapter liest ausschliesslich lokale Szenariodateien und uebergibt sie an denselben
 * Parser wie der Live-Adapter. Die Tests belegen die vollstaendige Kette fuer T01, die
 * Mehrdeutigkeit fuer T11, das fehlende vorherige Log fuer T12b und den erklaerten
 * Parsefehler fuer T12c.</p>
 */
class FixtureClusterReaderTest {

    private static final JsonMapper MAPPER = FixtureHarness.mapper();

    @Test
    @DisplayName("T01: Workload -> Revision -> Pod ueber UID, Events ueber Objekt-UID, Logs gelesen")
    void t01CollectsFullOwnershipChain() {
        ClusterCollection cluster = FixtureHarness.cluster("t01-liveness-startup", MAPPER);

        WorkloadSnapshot workload = cluster.workload();
        assertNotNull(workload, "Der Workload muss erfasst sein.");
        assertEquals("Deployment", workload.kind());
        assertEquals(FixtureHarness.NAMESPACE, workload.namespace());
        assertEquals("fixture-modelfinder", workload.name());
        assertEquals("fixture-deployment-modelfinder-001", workload.uid());
        assertEquals(7L, workload.generation());
        assertEquals(1, workload.desiredReplicas());
        assertEquals(0, workload.readyReplicas());
        assertTrue(workload.conditions().stream()
                .anyMatch(condition -> "ProgressDeadlineExceeded".equals(condition.reason())),
                "Der Rolloutfehler des Deployments muss als Condition erhalten bleiben.");

        // Revision ueber die Owner-UID des Workloads.
        List<RevisionSnapshot> revisions = FixtureHarness.revisionsOwnedByWorkload(cluster);
        assertEquals(1, revisions.size(), "Genau eine Revision gehoert zum Workload.");
        RevisionSnapshot revision = revisions.get(0);
        assertEquals("ReplicaSet", revision.kind());
        assertEquals("fixture-rs-001", revision.uid());
        assertEquals(3, revision.revisionNumber());
        assertTrue(revision.images().stream().anyMatch(image -> image.endsWith("modelfinder:2026.10.1")),
                "Das Image der Revision muss aus dem Template stammen.");
        assertTrue(revision.probes().stream().anyMatch(probe -> probe.initialDelaySeconds() != null
                        && probe.initialDelaySeconds() == 10 && probe.periodSeconds() == 10
                        && probe.failureThreshold() == 3),
                "Die Revisionsprobe muss die synthetischen Werte 10/10/3 tragen.");

        // Pod ueber die Controller-Owner-UID der Revision.
        OwnershipGraph graph = new OwnershipGraph(cluster);
        List<PodSnapshot> pods = graph.podsOf(revisions);
        assertEquals(1, pods.size(), "Genau ein Pod gehoert zur Revision.");
        PodSnapshot pod = pods.get(0);
        assertEquals("fixture-pod-001", pod.uid());
        assertEquals("fixture-modelfinder-7d9c4b6f5c-p4x2q", pod.name());

        OwnershipGraph.PodRevisionLink link = graph.link(pod);
        assertEquals(ch.so.agi.ops.domain.LinkStrength.EXACT, link.strength(),
                "Die Pod-Revision-Zuordnung ist ueber die Owner-UID exakt.");
        assertEquals("fixture-rs-001", link.revision().uid());

        // Containerzustand: Restartcount 1, lastState Terminated.
        var main = pod.containers().get(0);
        assertNotNull(main);
        assertEquals(1, main.restartCount());
        assertEquals("modelfinder", main.name());
        assertNotNull(main.lastState());
        assertTrue(main.lastState().isTerminated());
        assertEquals(137, main.lastState().exitCode());
        assertTrue(main.observations().hadRestart());

        // Events ueber die Objekt-UID des Pods.
        assertFalse(cluster.events().isEmpty(), "Die Events des Pods muessen erfasst sein.");
        for (EventSnapshot event : cluster.events()) {
            assertEquals("fixture-pod-001", event.involvedObject().uid(),
                    "Jedes erfasste Event muss ueber die Objekt-UID zum Pod gehoeren.");
        }
        assertTrue(cluster.events().stream().anyMatch(event -> "Killing".equals(event.reason())),
                "Das Killing-Event muss erhalten bleiben.");
        assertTrue(cluster.events().stream().anyMatch(event -> "Unhealthy".equals(event.reason())
                        && event.message() != null && event.message().contains("Liveness probe failed")),
                "Das Liveness-Event muss erhalten bleiben.");
        // Dasselbe Event aus dem zweiten Format darf nicht als zweiter, unabhaengiger Beleg zaehlen.
        assertTrue(cluster.events().stream().anyMatch(EventSnapshot::isDuplicate),
                "Ein ueber beide Formate gelesenes Event muss als Duplikat markiert sein.");

        // Logs: aktuelles und vorheriges Log des Hauptcontainers.
        List<ContainerLog> logs = cluster.logs();
        ContainerLog current = logs.stream()
                .filter(log -> log.containerName().equals("modelfinder") && !log.previous())
                .findFirst().orElse(null);
        assertNotNull(current, "Das aktuelle Container-Log muss erfasst sein.");
        assertTrue(current.available(), "Das aktuelle Log ist vorhanden.");
        assertFalse(current.lines().isEmpty(), "Das aktuelle Log darf nicht leer sein.");
        assertTrue(current.lines().stream().anyMatch(line -> line.contains("Fachdateninitialisierung abgeschlossen")),
                "Der Inhalt des Logs muss aus der Szenariodatei stammen.");

        ContainerLog previous = logs.stream()
                .filter(log -> log.containerName().equals("modelfinder") && log.previous())
                .findFirst().orElse(null);
        assertNotNull(previous, "Bei Restartcount 1 muss ein vorheriges Log erfasst werden.");
        assertTrue(previous.available());
        assertNull(previous.errorCode(), "Das vorhandene vorherige Log darf keinen Fehlercode tragen.");

        assertTrue(cluster.selectionNotes().stream().anyMatch(note -> note.contains("t01-liveness-startup")),
                "Die Auswahlinweise muss das Szenario nennen.");
    }

    @Test
    @DisplayName("T11: zwei Revisionen, gleichnamige Pods mit anderer UID, Event der alten UID")
    void t11KeepsAmbiguityAndSeparatesPodsByUid() {
        ClusterCollection cluster = FixtureHarness.cluster("t11-ambiguous-revision", MAPPER);

        List<RevisionSnapshot> revisions = FixtureHarness.revisionsOwnedByWorkload(cluster);
        assertEquals(2, revisions.size(), "Beide ReplicaSets gehoeren zum Workload.");
        assertTrue(revisions.stream().allMatch(revision -> revision.images().stream()
                        .anyMatch(image -> image.endsWith(":2026.10.1"))),
                "Beide Revisionen tragen denselben Image-Tag.");

        OwnershipGraph graph = new OwnershipGraph(cluster);
        // Die Mehrdeutigkeit wird ueber die verbindliche Owner-UID-Regel festgestellt:
        // mehrere Revisionen mit demselben Workload-Owner sind kein exakter Nachweis.
        assertEquals(2, revisions.size(), "Beide Revisionen gehoeren ueber die Owner-UID zum Workload.");
        assertEquals(LinkStrength.AMBIGUOUS,
                FixtureHarness.buildToWorkloadLink(cluster, FixtureHarness.build("t11-ambiguous-revision", MAPPER)),
                "Mehrere plausible Revisionen mit gleichem Image-Tag sind mehrdeutig.");

        List<String> duplicates = graph.duplicatePodNames();
        assertEquals(1, duplicates.size(), "Gleichnamige Pods muessen erkannt werden.");
        assertTrue(duplicates.get(0).contains("2 UIDs"),
                "Beide UIDs des gleichnamigen Pods muessen genannt sein: " + duplicates);

        List<PodSnapshot> pods = graph.podsOf(revisions);
        assertEquals(2, pods.size());
        assertEquals(2, pods.stream().map(PodSnapshot::uid).distinct().count());
        assertEquals(1, pods.stream().map(PodSnapshot::name).distinct().count());

        // Das Event gehoert zur alten UID und darf nicht der neuen Instanz zugeordnet werden.
        assertTrue(cluster.events().stream().anyMatch(event -> "fixture-pod-001".equals(event.involvedObject().uid())),
                "Das Event der alten Pod-UID muss erfasst sein.");
        assertFalse(cluster.events().stream().anyMatch(event -> "fixture-pod-002".equals(event.involvedObject().uid())),
                "Es darf kein Event geben, das der neuen Pod-UID zugeordnet waere.");
    }

    @Test
    @DisplayName("T12b: fehlendes vorheriges Log ergibt NO_PREVIOUS_LOG und kein leeres Erfolgslog")
    void t12bMissingPreviousLogIsAnExplainedError() {
        ClusterCollection cluster = FixtureHarness.cluster("t12b-previous-log-missing", MAPPER);
        List<ContainerLog> logs = cluster.logs();

        ContainerLog previous = logs.stream()
                .filter(log -> log.containerName().equals("modelfinder") && log.previous())
                .findFirst().orElse(null);
        assertNotNull(previous, "Bei Restartcount 2 muss ein vorheriges Log versucht werden.");
        assertFalse(previous.available(), "Ein fehlendes vorheriges Log ist nicht verfuegbar.");
        assertEquals(ErrorCode.NO_PREVIOUS_LOG.name(), previous.errorCode(),
                "Der Fehlercode muss NO_PREVIOUS_LOG sein.");
        assertTrue(previous.lines().isEmpty());
        assertFalse(previous.errorMessage().isBlank(), "Der Fehler muss erklaert werden.");

        ContainerLog current = logs.stream()
                .filter(log -> log.containerName().equals("modelfinder") && !log.previous())
                .findFirst().orElse(null);
        assertNotNull(current);
        assertTrue(current.available(), "Das aktuelle Log bleibt verfuegbar.");
    }

    @Test
    @DisplayName("T12c: ungueltiges JSON ergibt SOURCE_PARSE_ERROR bei erhaltenen Teilbelegen")
    void t12cInvalidJsonIsReportedAsParseError() {
        OpsConfig config = FixtureHarness.config("t12c-invalid-json");
        var target = FixtureHarness.target("t12c-invalid-json", config);
        SourceResult<ClusterCollection> result = FixtureHarness
                .clusterReader(config, "t12c-invalid-json", MAPPER).collect(target, target.budget());

        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.SOURCE_PARSE_ERROR),
                "Der ungueltige JSON-Inhalt muss als SOURCE_PARSE_ERROR gemeldet werden: " + result.errors());
        assertEquals(SourceStatus.TRUNCATED, result.status(),
                "Ein Teilausfall ergibt einen partiellen, keinen vollstaendigen Status.");
        assertNotNull(result.data().workload(), "Die verwertbaren Teilbelege bleiben erhalten.");
        assertFalse(result.data().pods().isEmpty(), "Die Pods bleiben erhalten.");
        assertTrue(result.data().events().isEmpty(), "Aus der ungueltigen Datei entstehen keine Events.");
    }

    @Test
    @DisplayName("T12d: nicht erlaubte Events ergeben NOT_ALLOWED/FORBIDDEN ohne erfundene Events")
    void t12dForbiddenEventsAreNotInvented() {
        OpsConfig config = FixtureHarness.config("t12d-events-forbidden");
        var target = FixtureHarness.target("t12d-events-forbidden", config);
        SourceResult<ClusterCollection> result = FixtureHarness
                .clusterReader(config, "t12d-events-forbidden", MAPPER).collect(target, target.budget());

        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.FORBIDDEN),
                "Die verbotene Abfrage muss als FORBIDDEN gemeldet werden: " + result.errors());
        assertEquals(SourceStatus.TRUNCATED, result.status());
        assertTrue(result.data().events().isEmpty(), "Es duerfen keine Events erfunden werden.");
        assertNotNull(result.data().workload(), "Die uebrigen Teilbelege bleiben erhalten.");
    }

    @Test
    @DisplayName("Ein Jenkins-Fehler veraendert die Clusterbelege nicht")
    void jenkinsFailureDoesNotAffectClusterEvidence() {
        var config = FixtureHarness.config("t12a-jenkins-forbidden");
        var target = FixtureHarness.target("t12a-jenkins-forbidden", config);
        SourceResult<ClusterCollection> result = FixtureHarness
                .clusterReader(config, "t12a-jenkins-forbidden", MAPPER).collect(target, target.budget());
        assertTrue(result.status().isUsable(),
                "Eine nicht erreichbare Jenkins-Quelle darf die Clusterbelege nicht unbrauchbar machen.");
        assertNotNull(result.data().workload());
        assertFalse(result.data().events().isEmpty(),
                "Die im Szenario vorhandenen Events bleiben erhalten.");
        assertTrue(result.errors().isEmpty(),
                "Die Clusterquelle selbst meldet keinen Jenkins-Fehler: " + result.errors());
    }

    @Test
    @DisplayName("Ein fehlendes Szenario ergibt einen erklaerten Fehler, keinen leeren Erfolg")
    void missingScenarioIsAnExplainedError() {
        OpsConfig config = FixtureHarness.config("t01-liveness-startup");
        var target = FixtureHarness.target("t01-liveness-startup", config);
        // Ein Szenario, das im Wurzelverzeichnis nicht existiert.
        var brokenTarget = new ch.so.agi.ops.application.port.ResolvedTarget(target.targetId(), config,
                ch.so.agi.ops.domain.DataMode.FIXTURE, target.applicationId(), target.environment(),
                target.definition(), target.buildRef(), null, target.resolvedAt(), target.expiresAt(),
                target.budget(), List.of(), "t99-does-not-exist");
        SourceResult<ClusterCollection> result = FixtureHarness
                .clusterReader(config, "t01-liveness-startup", MAPPER).collect(brokenTarget, target.budget());
        assertEquals(SourceStatus.UNREACHABLE, result.status());
        assertFalse(result.errors().isEmpty(), "Ein fehlendes Szenario muss erklaert werden.");
    }

    @Test
    @DisplayName("T10b: DeploymentConfig -> ReplicationController -> Pod")
    void t10bFollowsReplicationControllerChain() {
        ClusterCollection cluster = FixtureHarness.cluster("t10b-deploymentconfig", MAPPER);
        WorkloadSnapshot workload = cluster.workload();
        assertNotNull(workload);
        assertEquals("DeploymentConfig", workload.kind());
        assertEquals("fixture-legacy-application", workload.name());
        assertEquals(FixtureHarness.NAMESPACE_SERVICE_B, cluster.namespace());

        List<RevisionSnapshot> revisions = FixtureHarness.revisionsOwnedByWorkload(cluster);
        assertEquals(1, revisions.size(), "Genau ein ReplicationController gehoert zur DeploymentConfig.");
        assertEquals("ReplicationController", revisions.get(0).kind());
        assertEquals(3, revisions.get(0).revisionNumber());

        OwnershipGraph graph = new OwnershipGraph(cluster);
        List<PodSnapshot> pods = graph.podsOf(revisions);
        assertEquals(1, pods.size());
        assertEquals("fixture-rc-001", pods.get(0).ownerReferences().get(0).uid(),
                "Der Pod muss ueber den ReplicationController zugeordnet sein.");
        assertEquals("fixture-pod-101", pods.get(0).uid());
    }
}
