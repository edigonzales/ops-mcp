package ch.so.agi.ops.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Umgebungsdefinition. Ungeloeste {@code null}-Pflichtfelder fuehren fuer diesen Eintrag zu
 * {@code CONFIG_INCOMPLETE}; es gibt keinen Defaultnamespace.
 *
 * @param cluster       Kennung des Clusters
 * @param namespace     Namespace
 * @param workload      Workload-Auswahl
 * @param deployment    Jenkins-Zuordnung
 * @param configuration Konfigurationszuordnung
 */
public record EnvironmentDefinition(
        String cluster,
        String namespace,
        WorkloadDefinition workload,
        DeploymentDefinition deployment,
        ConfigurationDefinition configuration) {

    public EnvironmentDefinition {
        Objects.requireNonNull(cluster, "cluster");
    }

    public List<String> missingFields() {
        List<String> missing = new java.util.ArrayList<>();
        if (namespace == null || namespace.isBlank()) {
            missing.add("namespace");
        }
        if (workload == null) {
            missing.add("workload");
        } else {
            missing.addAll(workload.missingFields());
        }
        if (deployment == null) {
            missing.add("deployment");
        } else {
            if (deployment.jenkins() == null || deployment.jenkins().isBlank()) {
                missing.add("deployment.jenkins");
            }
            if (deployment.jobPath() == null || deployment.jobPath().isEmpty()) {
                missing.add("deployment.jobPath");
            }
        }
        if (configuration == null) {
            missing.add("configuration");
        } else {
            missing.addAll(configuration.missingFields());
        }
        return missing;
    }

    public boolean isComplete() {
        return missingFields().isEmpty();
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("cluster", cluster);
        map.put("namespace", namespace);
        map.put("workloadKind", workload == null || workload.kind() == null ? null : workload.kind().name());
        map.put("workloadName", workload == null ? null : workload.name());
        map.put("containerNames", workload == null ? List.of() : workload.containerNames());
        map.put("jenkins", deployment == null ? null : deployment.jenkins());
        map.put("jobPath", deployment == null ? null : deployment.jobPath());
        map.put("repository", configuration == null ? null : configuration.repository());
        map.put("complete", isComplete());
        return map;
    }
}
