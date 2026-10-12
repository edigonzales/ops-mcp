package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadCondition;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft die Abbildung der {@code oc}-Rohantworten auf die Domaenenrecords, die Redaktion
 * sensitiver Werte, die Eventdeduplizierung und die Fehlerfaelle.
 */
class OcJsonParserTest {

    private static final String NS = OcFixtures.NAMESPACE;

    private final OcJsonParser parser = OcJsonParser.DEFAULT;

    @Test
    void deploymentWirdVollstaendigAbgebildet() {
        WorkloadSnapshot deployment = parser.parseDeployment(OcFixtures.DEPLOYMENT_JSON, NS);

        assertEquals(WorkloadSnapshot.KIND_DEPLOYMENT, deployment.kind());
        assertEquals("apps/v1", deployment.apiVersion());
        assertEquals(NS, deployment.namespace());
        assertEquals(OcFixtures.WORKLOAD, deployment.name());
        assertEquals(OcFixtures.DEPLOYMENT_UID, deployment.uid());
        assertEquals(7L, deployment.generation());
        assertEquals(7L, deployment.observedGeneration());
        assertEquals(1, deployment.desiredReplicas());
        assertEquals(1, deployment.updatedReplicas());
        assertEquals(0, deployment.readyReplicas());
        assertEquals(0, deployment.availableReplicas());
        assertEquals(Boolean.FALSE, deployment.rollingOut());
        assertEquals(Instant.parse("2026-10-01T09:58:00Z"), deployment.creationTimestamp());
        assertEquals("fixture-pod-template-uid-3", deployment.podTemplateUid());
        assertEquals("3", deployment.revisionHint());
        assertEquals(Map.of("app", OcFixtures.WORKLOAD), deployment.labels());
        assertEquals("1042", deployment.rawResourceVersion());

        assertEquals(2, deployment.conditions().size());
        WorkloadCondition progressing = deployment.conditions().get(0);
        assertEquals("Progressing", progressing.type());
        assertEquals("True", progressing.status());
        assertEquals("ReplicaSetUpdated", progressing.reason());
        assertTrue(progressing.message().contains("is progressing"));
        assertEquals(Instant.parse("2026-10-01T09:58:00Z"), progressing.lastTransitionTime());
        assertEquals(Instant.parse("2026-10-01T10:00:02Z"), progressing.lastUpdateTime());
        WorkloadCondition available = deployment.conditions().get(1);
        assertEquals("Available", available.type());
        assertFalse(available.isTrue());
        assertEquals("MinimumReplicasUnavailable", available.reason());
    }

    @Test
    void deploymentTemplateProbesWerdenAlsMapGefuehrt() {
        WorkloadSnapshot deployment = parser.parseDeployment(OcFixtures.DEPLOYMENT_JSON, NS);

        assertEquals(Set.of(OcFixtures.CONTAINER_MAIN, OcFixtures.CONTAINER_SIDECAR),
                deployment.templateProbes().keySet());
        ProbeConfig mainProbe = deployment.templateProbes().get(OcFixtures.CONTAINER_MAIN);
        assertEquals(ProbeConfig.ProbeKind.LIVENESS, mainProbe.kind(),
                "Eine Map kann nur eine Probe je Container fuehren; Prioritaet ist Liveness.");
        assertEquals(ProbeConfig.HandlerKind.HTTP_GET, mainProbe.handlerKind());
        assertEquals("/healthz", mainProbe.httpPath());
        assertEquals("http", mainProbe.httpPort());
        assertEquals("HTTP", mainProbe.httpScheme());
        assertEquals(10, mainProbe.initialDelaySeconds());
        assertEquals(10, mainProbe.periodSeconds());
        assertEquals(2, mainProbe.timeoutSeconds());
        assertEquals(1, mainProbe.successThreshold());
        assertEquals(3, mainProbe.failureThreshold());
        assertEquals(30, mainProbe.terminationGracePeriodSeconds());
        assertFalse(mainProbe.redacted());
        ProbeConfig sidecarProbe = deployment.templateProbes().get(OcFixtures.CONTAINER_SIDECAR);
        assertEquals(ProbeConfig.ProbeKind.READINESS, sidecarProbe.kind());
        assertEquals(ProbeConfig.HandlerKind.TCP_SOCKET, sidecarProbe.handlerKind());
        assertEquals("9090", sidecarProbe.httpPort());
    }

    @Test
    void deploymentConfigWirdAbgebildet() {
        WorkloadSnapshot config = parser.parseDeploymentConfig(OcFixtures.DEPLOYMENT_CONFIG_JSON, NS);

        assertEquals(WorkloadSnapshot.KIND_DEPLOYMENT_CONFIG, config.kind());
        assertEquals("apps.openshift.io/v1", config.apiVersion());
        assertEquals("fixture-dc-uid-1", config.uid());
        assertEquals("4", config.revisionHint(), "status.latestVersion ist die Revisionsnummer.");
        assertEquals(Map.of("app", OcFixtures.WORKLOAD, "deploymentconfig", OcFixtures.WORKLOAD),
                config.labels(), "labels fuehrt den Pod-Selektor fuer die Kandidatenauswahl.");
        assertEquals(1, config.conditions().size());
        assertTrue(config.conditions().get(0).isTrue());
        assertTrue(config.isDeploymentLike());
        assertEquals(45, config.templateProbes().get(OcFixtures.CONTAINER_MAIN).terminationGracePeriodSeconds());
    }

    @Test
    void unbekannterOderFehlenderWorkloadTypErgibtUnsupportedWorkload() {
        OcParseException falscherTyp = assertThrows(OcParseException.class,
                () -> parser.parseDeploymentConfig(OcFixtures.WRONG_KIND_JSON, NS));
        assertEquals(ErrorCode.UNSUPPORTED_WORKLOAD, falscherTyp.code());
        assertFalse(falscherTyp.getMessage().contains("fixture-deployment-uid-1"),
                "Die Meldung enthaelt keinen Antwortinhalt.");

        OcParseException fehlenderTyp = assertThrows(OcParseException.class,
                () -> parser.parseDeploymentConfig("{\"metadata\":{\"name\":\"x\"}}", NS));
        assertEquals(ErrorCode.UNSUPPORTED_WORKLOAD, fehlenderTyp.code());

        OcParseException deploymentStattConfig = assertThrows(OcParseException.class,
                () -> parser.parseDeployment(OcFixtures.DEPLOYMENT_CONFIG_JSON, NS));
        assertEquals(ErrorCode.UNSUPPORTED_WORKLOAD, deploymentStattConfig.code());
    }

    @Test
    void replicaSetsLiefernRevisionenMitHashUndProbes() {
        List<RevisionSnapshot> revisions = parser.parseReplicaSets(OcFixtures.REPLICA_SETS_JSON, NS);

        assertEquals(2, revisions.size());
        RevisionSnapshot alt = revisions.get(0);
        assertEquals(RevisionSnapshot.KIND_REPLICA_SET, alt.kind());
        assertEquals(OcFixtures.RS_OLD_UID, alt.uid());
        assertEquals(2, alt.revisionNumber());
        assertEquals("5b8d4c7f9a", alt.podTemplateHash());
        assertEquals(0, alt.replicas());
        assertEquals(1, alt.ownerReferences().size());
        assertTrue(alt.ownerReferences().get(0).isController());
        assertEquals(OcFixtures.DEPLOYMENT_UID, alt.ownerReferences().get(0).uid());
        assertEquals(Map.of("app", OcFixtures.WORKLOAD, "pod-template-hash", "5b8d4c7f9a"), alt.selector());

        RevisionSnapshot neu = revisions.get(1);
        assertEquals(OcFixtures.RS_NEW_UID, neu.uid());
        assertEquals(3, neu.revisionNumber());
        assertEquals(Instant.parse("2026-10-01T09:58:01Z"), neu.creationTimestamp());
        assertEquals(List.of("registry.example.invalid/fixture/modelfinder:42"), neu.images());
        assertEquals(Map.of(OcFixtures.CONTAINER_MAIN, "registry.example.invalid/fixture/modelfinder:42"),
                neu.containerImages());
        assertEquals("fixture-pod-template-uid-3", neu.podTemplateUid());
        assertEquals(1, neu.probes().size());
        ProbeConfig probe = neu.probes().get(0);
        assertEquals(ProbeConfig.ProbeKind.LIVENESS, probe.kind());
        assertEquals(OcFixtures.CONTAINER_MAIN, probe.containerName());
        assertEquals(10, probe.initialDelaySeconds(),
                "Die aeltere Revision fuehrt eine abweichende Probe; sie bleibt getrennt erhalten.");
        assertEquals(5, alt.probes().get(0).initialDelaySeconds());
    }

    @Test
    void replicationControllersLiefernRevisionUndSelektor() {
        List<RevisionSnapshot> revisions =
                parser.parseReplicationControllers(OcFixtures.REPLICATION_CONTROLLERS_JSON, NS);

        assertEquals(1, revisions.size());
        RevisionSnapshot controller = revisions.get(0);
        assertEquals(RevisionSnapshot.KIND_REPLICATION_CONTROLLER, controller.kind());
        assertEquals(OcFixtures.RC_UID, controller.uid());
        assertEquals(4, controller.revisionNumber());
        assertEquals(Map.of("app", OcFixtures.WORKLOAD, "deploymentconfig", OcFixtures.WORKLOAD),
                controller.selector());
        assertNull(controller.podTemplateHash());
        assertEquals(1, controller.probes().size());
        assertEquals(OcFixtures.CONTAINER_MAIN, controller.probes().get(0).containerName());
    }

    @Test
    void podsBildenInitContainerSidecarsUndContainerStatusAb() {
        List<PodSnapshot> pods = parser.parsePods(OcFixtures.PODS_JSON, NS, 8);

        assertEquals(2, pods.size());
        PodSnapshot restarting = pods.get(0);
        assertEquals(OcFixtures.POD_A, restarting.name());
        assertEquals(OcFixtures.POD_A_UID, restarting.uid());
        assertEquals(NS, restarting.namespace());
        assertEquals("Running", restarting.phase());
        assertEquals("fixture-node-1", restarting.nodeName());
        assertEquals("Always", restarting.restartPolicy());
        assertEquals(Instant.parse("2026-10-01T09:58:05Z"), restarting.creationTimestamp());
        assertNull(restarting.deletionTimestamp());
        assertEquals(4, restarting.conditions().size());
        assertFalse(restarting.isReady());
        assertFalse(restarting.isContainersReady());
        assertEquals(List.of("ContainersReady", "Ready"), restarting.notReadyConditionTypes());
        PodSnapshot.PodCondition ready = restarting.conditions().get(3);
        assertEquals("Ready", ready.type());
        assertEquals("ContainersNotReady", ready.reason());
        assertTrue(ready.message().contains("unready status"));
        assertEquals(Instant.parse("2026-10-01T10:00:20Z"), ready.lastTransitionTime());

        assertEquals(1, restarting.ownerReferences().size());
        assertTrue(restarting.ownerReferences().get(0).isController());
        assertEquals(OcFixtures.RS_NEW_UID, restarting.ownerReferences().get(0).uid());
        assertEquals("ReplicaSet", restarting.ownerReferences().get(0).kind());

        assertEquals(2, restarting.containers().size());
        assertEquals(ContainerSnapshot.ContainerClass.MAIN, restarting.containers().get(1).containerClass(),
                "spec.containers bleibt MAIN; SIDECAR wird nicht vergeben.");
        assertEquals(1, restarting.initContainers().size());
        assertEquals(ContainerSnapshot.ContainerClass.INIT, restarting.initContainers().get(0).containerClass());
        assertEquals("Completed", restarting.initContainers().get(0).state().reason());
        assertEquals(0, restarting.initContainers().get(0).state().exitCode());
        assertEquals(Instant.parse("2026-10-01T09:58:06Z"), restarting.initContainers().get(0).startedAt());

        ContainerSnapshot main = restarting.container(OcFixtures.CONTAINER_MAIN);
        assertNotNull(main);
        assertEquals("registry.example.invalid/fixture/modelfinder:42", main.image());
        assertTrue(main.imageId().contains("sha256:2222"));
        assertFalse(main.ready());
        assertEquals(2, main.restartCount());
        assertEquals(Boolean.FALSE, main.started());
        assertEquals(ContainerSnapshot.ContainerState.StateKind.WAITING, main.state().kind());
        assertEquals("CrashLoopBackOff", main.state().reason());
        assertEquals(ContainerSnapshot.ContainerState.StateKind.TERMINATED, main.lastState().kind());
        assertEquals("Error", main.lastState().reason());
        assertEquals(137, main.lastState().exitCode());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), main.lastState().startedAt());
        assertEquals(Instant.parse("2026-10-01T10:00:31Z"), main.lastState().finishedAt());
        assertNull(main.startedAt(), "Der aktuelle Zustand ist waiting und fuehrt keine Startzeit.");
        assertEquals(Instant.parse("2026-10-01T10:00:31Z"), main.finishedAt());
        assertTrue(main.observations().hadRestart());
        assertEquals("Error", main.observations().lastTerminationReason());

        assertEquals("7c9f5d8b64", restarting.revisionHint());
        assertEquals(Map.of("app", OcFixtures.WORKLOAD, "pod-template-hash", "7c9f5d8b64"), restarting.labels());
        assertNull(restarting.podTemplateUid(), "Ein Pod fuehrt kein spec.template mit eigener UID.");
        assertEquals(2, restarting.maxRestartCount());
        assertEquals(List.of(OcFixtures.CONTAINER_SIDECAR), OcJsonParser.sidecarCandidates(restarting));

        PodSnapshot healthy = pods.get(1);
        assertTrue(healthy.isReady());
        assertEquals(0, healthy.container(OcFixtures.CONTAINER_MAIN).restartCount());
        assertNull(healthy.container(OcFixtures.CONTAINER_MAIN).lastState());

        assertEquals(1, parser.parsePods(OcFixtures.PODS_JSON, NS, 1).size());
    }

    @Test
    void probesWerdenVollstaendigUndRedigiertGeparst() {
        PodSnapshot pod = parser.parsePods(OcFixtures.PODS_JSON, NS, 8).get(0);
        List<ProbeConfig> mainProbes = pod.probesFromPod().stream()
                .filter(probe -> probe.containerName().equals(OcFixtures.CONTAINER_MAIN))
                .toList();
        assertEquals(3, mainProbes.size());

        ProbeConfig readiness = mainProbes.stream()
                .filter(probe -> probe.kind() == ProbeConfig.ProbeKind.READINESS).findFirst().orElseThrow();
        assertEquals(ProbeConfig.HandlerKind.HTTP_GET, readiness.handlerKind());
        assertEquals("/healthz", readiness.httpPath());
        assertEquals("8080", readiness.httpPort());
        assertEquals("HTTP", readiness.httpScheme());
        assertEquals(List.of("X-Fixture-Header"), readiness.httpHeaderNames(),
                "Nur die Header-NAMEN werden uebernommen.");
        assertTrue(readiness.redacted());
        assertTrue(readiness.redactionNote().contains("Header-Werte"));

        ProbeConfig startup = mainProbes.stream()
                .filter(probe -> probe.kind() == ProbeConfig.ProbeKind.STARTUP).findFirst().orElseThrow();
        assertEquals(ProbeConfig.HandlerKind.EXEC, startup.handlerKind());
        assertTrue(startup.execCommandRedacted());
        assertTrue(startup.redacted());
        assertTrue(startup.redactionNote().contains("exec"));
        assertEquals(15, startup.failureThreshold());
        assertEquals(2, startup.periodSeconds());

        String gerendert = pod.toString() + pod.probesFromPod() + pod.containers() + pod.initContainers();
        assertFalse(gerendert.contains(OcFixtures.HEADER_CANARY), "Header-Wert darf nicht uebernommen werden.");
        assertFalse(gerendert.contains("fixture-canary-header"), "Header-Wert darf nicht uebernommen werden.");
        assertFalse(gerendert.contains("/bin/sh"), "exec-Argumente duerfen nicht uebernommen werden.");
        assertFalse(gerendert.contains("test -f /tmp/fixture-ready"),
                "exec-Argumente duerfen nicht uebernommen werden.");
        assertTrue(gerendert.contains("X-Fixture-Header"), "Der Header-Name bleibt erhalten.");
    }

    @Test
    void containerEnvUndSecretVerweiseWerdenNichtGelesen() {
        WorkloadSnapshot deployment = parser.parseDeployment(OcFixtures.DEPLOYMENT_JSON, NS);

        String gerendert = deployment.toString() + deployment.templateProbes() + deployment.conditions();
        assertFalse(gerendert.contains("FIXTURE_TOKEN"));
        assertFalse(gerendert.contains("fixture-secret"));
        assertFalse(gerendert.contains("secretKeyRef"));
        assertFalse(gerendert.contains(OcFixtures.SECRET_CANARY));
    }

    @Test
    void containerzustandsmeldungenWerdenRedigiert() {
        PodSnapshot pod = parser.parsePods(OcFixtures.PODS_JSON, NS, 8).get(0);

        ContainerSnapshot main = pod.container(OcFixtures.CONTAINER_MAIN);
        assertFalse(main.state().message().contains(OcFixtures.STATE_CANARY));
        assertTrue(main.state().message().contains("[redigiert]"));
    }

    @Test
    void gleichnamigerPodMitAndererUidBleibtGetrennt() {
        PodSnapshot aktuell = parser.parsePods(OcFixtures.PODS_JSON, NS, 8).get(0);
        PodSnapshot alt = parser.parsePod(OcFixtures.POD_OLD_GENERATION_JSON, NS);

        assertEquals(aktuell.name(), alt.name());
        assertFalse(aktuell.uid().equals(alt.uid()),
                "Gleichnamige Pods mit anderer UID sind getrennte Belege (T11).");
        assertEquals("fixture-pod-uid-old-99", alt.uid());
        assertEquals("5b8d4c7f9a", alt.revisionHint());
        assertEquals(OcFixtures.RS_OLD_UID, alt.ownerReferences().get(0).uid());
    }

    @Test
    void podOhneStatusHatUnbekanntenRestartcount() {
        PodSnapshot pod = parser.parsePod(OcFixtures.POD_WITHOUT_STATUS_JSON, NS);

        assertEquals("Pending", pod.phase());
        assertNull(pod.container(OcFixtures.CONTAINER_MAIN).restartCount());
        assertNull(pod.container(OcFixtures.CONTAINER_MAIN).state());
    }

    @Test
    void eventsImCoreFormatWerdenAbgebildet() {
        List<EventSnapshot> events = parser.parseEventsCore(OcFixtures.EVENTS_CORE_JSON, 100);

        assertEquals(7, events.size());
        EventSnapshot unhealthy = events.get(0);
        assertEquals(EventSnapshot.FORMAT_CORE, unhealthy.eventFormat());
        assertEquals("Pod", unhealthy.involvedObject().kind());
        assertEquals(OcFixtures.POD_A, unhealthy.involvedObject().name());
        assertEquals(OcFixtures.POD_A_UID, unhealthy.involvedObject().uid());
        assertEquals("Unhealthy", unhealthy.reason());
        assertEquals("Warning", unhealthy.type());
        assertEquals("kubelet", unhealthy.reportingComponent());
        assertEquals(3, unhealthy.count());
        assertTrue(unhealthy.isAggregated());
        assertNull(unhealthy.eventTime(), "core/v1 fuehrt kein eventTime.");
        assertEquals(Instant.parse("2026-10-01T10:00:10Z"), unhealthy.firstTimestamp());
        assertEquals(Instant.parse("2026-10-01T10:00:30Z"), unhealthy.lastTimestamp());
        assertEquals(Instant.parse("2026-10-01T10:00:30Z"), unhealthy.bestKnownTime());
        assertEquals("fixture-event-uid-1", unhealthy.uid());
        assertEquals("/api/v1/namespaces/" + NS + "/events", unhealthy.sourcePath());
        assertNotNull(unhealthy.observedAt());

        EventSnapshot ohneZeiten = events.get(6);
        assertEquals("Scheduled", ohneZeiten.reason());
        assertNull(ohneZeiten.firstTimestamp());
        assertNull(ohneZeiten.lastTimestamp());
        assertNull(ohneZeiten.eventTime());
        assertNull(ohneZeiten.bestKnownTime(), "Fehlende Zeiten bleiben null und werden nicht erfunden.");
    }

    @Test
    void eventMeldungenWerdenRedigiert() {
        List<EventSnapshot> events = parser.parseEventsCore(OcFixtures.EVENTS_CORE_JSON, 100);

        EventSnapshot failed = events.get(3);
        assertFalse(failed.message().contains(OcFixtures.EVENT_CANARY));
        assertTrue(failed.message().contains("[redigiert]"));
        assertTrue(failed.message().contains("Failed to pull image"));
    }

    @Test
    void eventsImEventsK8sIoFormatWerdenAbgebildet() {
        List<EventSnapshot> events = parser.parseEventsEventsK8sIo(OcFixtures.EVENTS_K8S_IO_JSON, 100);

        assertEquals(3, events.size());
        EventSnapshot oom = events.get(1);
        assertEquals(EventSnapshot.FORMAT_EVENTS_K8S_IO, oom.eventFormat());
        assertEquals("OOMKilling", oom.reason());
        assertEquals("kubelet", oom.reportingComponent());
        assertEquals(2, oom.count(), "series.count hat Vorrang vor deprecatedCount.");
        assertEquals(Instant.parse("2026-10-01T10:00:20Z"), oom.eventTime());
        assertEquals(Instant.parse("2026-10-01T10:00:05Z"), oom.firstTimestamp());
        assertEquals(Instant.parse("2026-10-01T10:00:25Z"), oom.lastTimestamp());
        assertEquals("/apis/events.k8s.io/v1/namespaces/" + NS + "/events", oom.sourcePath());
    }

    @Test
    void zweitesFormatWirdAlsDuplikatMarkiertUndNichtDoppeltGezaehlt() {
        List<EventSnapshot> core = parser.parseEventsCore(OcFixtures.EVENTS_CORE_JSON, 100);
        List<EventSnapshot> eventsK8sIo = parser.parseEventsEventsK8sIo(OcFixtures.EVENTS_K8S_IO_JSON, 100);

        List<EventSnapshot> merged = parser.mergeEventSources(core, eventsK8sIo);

        assertEquals(10, merged.size());
        List<EventSnapshot> duplicates = merged.stream().filter(EventSnapshot::isDuplicate).toList();
        assertEquals(1, duplicates.size(), "Genau ein Event liegt in beiden Formaten vor.");
        EventSnapshot duplicate = duplicates.get(0);
        assertEquals(EventSnapshot.FORMAT_CORE, duplicate.duplicateOfFormat());
        assertEquals(EventSnapshot.FORMAT_EVENTS_K8S_IO, duplicate.eventFormat());
        assertEquals("Unhealthy", duplicate.reason());
        assertEquals(9, merged.stream().filter(event -> !event.isDuplicate()).count(),
                "Ein Duplikat zaehlt nicht als unabhaengiger zweiter Beleg.");
    }

    @Test
    void eventsOhneBelastbarenSchluesselWerdenNichtZusammengelegt() {
        EventSnapshot ohneAngaben = new EventSnapshot(null, null, null, null, null, null, null, null, null,
                EventSnapshot.FORMAT_CORE, null, Instant.parse("2026-10-01T10:00:00Z"), null, null);
        EventSnapshot zweiterOhneAngaben = new EventSnapshot(null, null, null, null, null, null, null, null, null,
                EventSnapshot.FORMAT_EVENTS_K8S_IO, null, Instant.parse("2026-10-01T10:00:01Z"), null, null);

        List<EventSnapshot> merged = parser.mergeEventSources(List.of(ohneAngaben),
                List.of(zweiterOhneAngaben));

        assertEquals(2, merged.size());
        assertTrue(merged.stream().noneMatch(EventSnapshot::isDuplicate));
    }

    @Test
    void ungueltigeAntwortenErgebenSourceParseError() {
        OcParseException keinJson = assertThrows(OcParseException.class,
                () -> parser.parseDeployment("{kein json", NS));
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, keinJson.code());
        assertFalse(keinJson.getMessage().contains("kein json"),
                "Die Meldung enthaelt keinen Antwortinhalt.");

        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, assertThrows(OcParseException.class,
                () -> parser.parseDeployment("[1,2,3]", NS)).code());
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, assertThrows(OcParseException.class,
                () -> parser.parsePod("", NS)).code());
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, assertThrows(OcParseException.class,
                () -> parser.parseReplicaSets("{\"items\":[{\"kind\":\"ReplicaSet\"}]}", NS)).code(),
                "Eine Revision ohne metadata.name ist keine gueltige Struktur.");
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, assertThrows(OcParseException.class,
                () -> parser.parseEventsCore("{\"items\":[{\"involvedObject\":{\"name\":\"x\"}}]}", 10)).code(),
                "Ein Event ohne involvedObject.kind ist keine verwertbare Zuordnung.");
    }

    @Test
    void erwartungswertWirdNichtAlsKillZeitpunktVerwendet() {
        WorkloadSnapshot deployment = parser.parseDeployment(OcFixtures.DEPLOYMENT_JSON, NS);
        ProbeConfig liveness = deployment.templateProbes().get(OcFixtures.CONTAINER_MAIN);
        assertEquals(ProbeConfig.ProbeKind.LIVENESS, liveness.kind());
        assertEquals(Integer.valueOf(40), liveness.simplifiedStartBudgetSeconds(),
                "10 + 3 * 10 ist hoechstens eine vereinfachte Orientierung.");

        Instant containerStart = Instant.parse("2026-10-01T10:00:00Z");
        Instant vereinfachterKillZeitpunkt = containerStart.plusSeconds(40);
        for (PodSnapshot pod : parser.parsePods(OcFixtures.PODS_JSON, NS, 8)) {
            assertKeinZeitpunkt(pod, vereinfachterKillZeitpunkt, new HashSet<>());
        }
        assertFalse(methodenNamenEnthalten(ProbeConfig.class, "kill"),
                "ProbeConfig fuehrt keinen Kill-Zeitpunkt, nur simplifiedStartBudgetSeconds.");
        assertFalse(methodenNamenEnthalten(OcJsonParser.class, "killtime"));
        assertFalse(methodenNamenEnthalten(OcJsonParser.class, "killzeitpunkt"));
    }

    private static boolean methodenNamenEnthalten(Class<?> type, String teil) {
        String gesucht = teil.toLowerCase(java.util.Locale.ROOT);
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().toLowerCase(java.util.Locale.ROOT).contains(gesucht)) {
                return true;
            }
        }
        return false;
    }

    /** Sucht rekursiv in Records, Collections und Maps nach einem unzulaessigen Zeitpunkt. */
    private static void assertKeinZeitpunkt(Object value, Instant verboten, Set<Object> besucht) {
        if (value == null || besucht.contains(value)) {
            return;
        }
        besucht.add(value);
        if (value instanceof Instant instant) {
            assertFalse(instant.equals(verboten),
                    "Der vereinfachte Erwartungswert darf nicht als Zeitpunkt gefuehrt werden.");
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : new ArrayList<>(collection)) {
                assertKeinZeitpunkt(element, verboten, besucht);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object element : new ArrayList<>(map.values())) {
                assertKeinZeitpunkt(element, verboten, besucht);
            }
            return;
        }
        if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    assertKeinZeitpunkt(component.getAccessor().invoke(value), verboten, besucht);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Recordkomponente nicht lesbar: " + component.getName(), ex);
                }
            }
        }
    }
}
