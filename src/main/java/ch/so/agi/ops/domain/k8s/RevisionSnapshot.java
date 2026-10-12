package ch.so.agi.ops.domain.k8s;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Revision eines Workloads: ReplicaSet (Deployment) oder ReplicationController (DeploymentConfig).
 *
 * <p>Label-Selektoren dienen der begrenzten Kandidatenauswahl, nicht als alleiniger
 * Zugehoerigkeitsbeweis. Bei mehreren plausiblen Revisionen mit gleichem Image-Tag bleibt die
 * Zuordnung mehrdeutig.</p>
 *
 * @param kind               ReplicaSet oder ReplicationController
 * @param namespace          Namespace
 * @param name               Name
 * @param uid                UID
 * @param ownerReferences    Owner-Kette zum Workload
 * @param replicas           spec.replicas
 * @param readyReplicas      status.readyReplicas
 * @param availableReplicas  status.availableReplicas
 * @param creationTimestamp  Erstellungszeit
 * @param revisionNumber     Revisionsnummer, soweit ausgewiesen (Deployment: revision-Annotation)
 * @param podTemplateHash    pod-template-hash, soweit vorhanden
 * @param images             Images aus dem Template
 * @param containerImages    Container -> Image aus dem Template
 * @param probes             Probe-Konfiguration aus dem Revisions-Template
 * @param podTemplateUid     UID des Pod-Templates, soweit ausgewiesen
 * @param labels             Labels des Revisionsobjekts
 * @param selector           Label-Selektor des Revisionsobjekts
 */
public record RevisionSnapshot(
        String kind,
        String namespace,
        String name,
        String uid,
        List<OwnerReference> ownerReferences,
        Integer replicas,
        Integer readyReplicas,
        Integer availableReplicas,
        Instant creationTimestamp,
        Integer revisionNumber,
        String podTemplateHash,
        List<String> images,
        Map<String, String> containerImages,
        List<ProbeConfig> probes,
        String podTemplateUid,
        Map<String, String> labels,
        Map<String, String> selector) {

    public static final String KIND_REPLICA_SET = "ReplicaSet";
    public static final String KIND_REPLICATION_CONTROLLER = "ReplicationController";

    public RevisionSnapshot {
        ownerReferences = ownerReferences == null ? List.of() : List.copyOf(ownerReferences);
        images = images == null ? List.of() : List.copyOf(images);
        containerImages = containerImages == null ? Map.of() : Map.copyOf(containerImages);
        probes = probes == null ? List.of() : List.copyOf(probes);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        selector = selector == null ? Map.of() : Map.copyOf(selector);
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("kind", kind);
        map.put("name", name);
        map.put("uid", uid);
        map.put("revisionNumber", revisionNumber);
        map.put("podTemplateHash", podTemplateHash);
        map.put("images", images);
        map.put("replicas", replicas);
        map.put("readyReplicas", readyReplicas);
        map.put("creationTimestamp", creationTimestamp == null ? null : creationTimestamp.toString());
        return map;
    }
}
