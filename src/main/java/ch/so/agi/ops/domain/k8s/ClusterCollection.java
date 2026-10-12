package ch.so.agi.ops.domain.k8s;

import java.util.List;
import java.util.Map;

/**
 * Gesamtergebnis der Clustererfassung.
 *
 * @param clusterId      Kennung des Clusters aus der Konfiguration
 * @param server         erwartete API-Serveradresse aus der Konfiguration
 * @param context        verwendeter kubeconfig-Context, soweit angegeben
 * @param namespace      erlaubter Namespace
 * @param workload       Workload, sofern gefunden
 * @param workloadRequestedKind angeforderte Workload-Art aus der Konfiguration
 * @param revisions      Revisionsobjekte (ReplicaSet/ReplicationController)
 * @param pods           erfasste Pods
 * @param events         erfasste, ueber die Objekt-UID zugeordnete Events
 * @param logs           erfasste Container-Logs
 * @param selectionNotes Hinweise zur Kandidatenauswahl und Mehrdeutigkeit
 */
public record ClusterCollection(
        String clusterId,
        String server,
        String context,
        String namespace,
        WorkloadSnapshot workload,
        String workloadRequestedKind,
        List<RevisionSnapshot> revisions,
        List<PodSnapshot> pods,
        List<EventSnapshot> events,
        List<ContainerLog> logs,
        List<String> selectionNotes) {

    public ClusterCollection {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
        pods = pods == null ? List.of() : List.copyOf(pods);
        events = events == null ? List.of() : List.copyOf(events);
        logs = logs == null ? List.of() : List.copyOf(logs);
        selectionNotes = selectionNotes == null ? List.of() : List.copyOf(selectionNotes);
    }

    public static ClusterCollection empty(String clusterId, String namespace, String requestedKind) {
        return new ClusterCollection(clusterId, null, null, namespace, null, requestedKind, List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    public PodSnapshot podByUid(String uid) {
        return pods.stream().filter(p -> uid != null && uid.equals(p.uid())).findFirst().orElse(null);
    }

    public List<RevisionSnapshot> revisionsOwnedBy(String workloadUid) {
        if (workloadUid == null) {
            return List.of();
        }
        return revisions.stream()
                .filter(r -> r.ownerReferences().stream().anyMatch(o -> workloadUid.equals(o.uid())))
                .toList();
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("clusterId", clusterId);
        map.put("namespace", namespace);
        map.put("workloadRequestedKind", workloadRequestedKind);
        map.put("workload", workload == null ? null : Map.of(
                "kind", workload.kind(),
                "name", workload.name(),
                "uid", String.valueOf(workload.uid()),
                "generation", String.valueOf(workload.generation()),
                "observedGeneration", String.valueOf(workload.observedGeneration()),
                "desiredReplicas", String.valueOf(workload.desiredReplicas()),
                "readyReplicas", String.valueOf(workload.readyReplicas())));
        map.put("revisionCount", revisions.size());
        map.put("podCount", pods.size());
        map.put("eventCount", events.size());
        map.put("logCount", logs.size());
        if (!selectionNotes.isEmpty()) {
            map.put("selectionNotes", selectionNotes);
        }
        return map;
    }
}
