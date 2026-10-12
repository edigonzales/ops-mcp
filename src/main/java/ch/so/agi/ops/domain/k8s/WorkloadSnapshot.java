package ch.so.agi.ops.domain.k8s;

import java.time.Instant;
import java.util.Map;

/**
 * Normalisierter Kubernetes-/OpenShift-Workload. Deckt Deployment/ReplicaSet/ReplicationController
 * und DeploymentConfig als Workload-Art ab.
 *
 * @param kind                Deployment, DeploymentConfig, ReplicaSet oder ReplicationController
 * @param apiVersion          apiVersion aus der Antwort
 * @param namespace           Namespace
 * @param name                Name
 * @param uid                 UID
 * @param generation          metadata.generation
 * @param observedGeneration  status.observedGeneration, falls vorhanden
 * @param desiredReplicas     spec.replicas
 * @param updatedReplicas     status.updatedReplicas
 * @param readyReplicas       status.readyReplicas
 * @param availableReplicas   status.availableReplicas
 * @param conditions          status.conditions mit Status/Reason/Message/Zeitbezug
 * @param rollingOut          true, wenn ein Rollout noch laeuft (updatedReplicas &lt; desired)
 * @param creationTimestamp   metadata.creationTimestamp
 * @param labels              ausgewaehlte Labels, insbesondere der Pod-Selector des Templates
 * @param podTemplateUid      UID des aktuellen Pod-Templates, soweit ausgewiesen
 * @param revisionHint        Revisionshinweis (DeploymentConfig: latestVersion; Deployment: Revision-Annotation), falls vorhanden
 * @param templateProbes      Probe-Konfiguration des aktuellen Templates, je Container
 * @param rawResourceVersion  resourceVersion, nur fuer Nachvollziehbarkeit
 */
public record WorkloadSnapshot(
        String kind,
        String apiVersion,
        String namespace,
        String name,
        String uid,
        Long generation,
        Long observedGeneration,
        Integer desiredReplicas,
        Integer updatedReplicas,
        Integer readyReplicas,
        Integer availableReplicas,
        java.util.List<WorkloadCondition> conditions,
        Boolean rollingOut,
        Instant creationTimestamp,
        Map<String, String> labels,
        String podTemplateUid,
        String revisionHint,
        Map<String, ProbeConfig> templateProbes,
        String rawResourceVersion) {

    public WorkloadSnapshot {
        conditions = conditions == null ? java.util.List.of() : java.util.List.copyOf(conditions);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        templateProbes = templateProbes == null ? Map.of() : Map.copyOf(templateProbes);
    }

    public static final String KIND_DEPLOYMENT = "Deployment";
    public static final String KIND_DEPLOYMENT_CONFIG = "DeploymentConfig";
    public static final String KIND_REPLICA_SET = "ReplicaSet";
    public static final String KIND_REPLICATION_CONTROLLER = "ReplicationController";

    public boolean isDeploymentLike() {
        return KIND_DEPLOYMENT.equals(kind) || KIND_DEPLOYMENT_CONFIG.equals(kind);
    }
}
