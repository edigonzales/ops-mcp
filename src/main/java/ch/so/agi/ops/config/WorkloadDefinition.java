package ch.so.agi.ops.config;

import java.util.List;
import java.util.Map;

/**
 * Workload-Auswahl innerhalb einer Umgebung.
 *
 * <p>{@code containerNames} ist eine explizite Auswahl innerhalb des ermittelten Workloads.
 * Eine leere Auswahl erlaubt die Erfassung der Containermetadaten, aber nicht automatisch
 * beliebige Logs; {@code doctor} fordert eine Auswahl oder eine ausdruecklich dokumentierte
 * lokale {@code allWorkloadContainers}-Policy.</p>
 *
 * @param kind                  Workload-Art (Deployment oder DeploymentConfig) oder {@code null}
 * @param name                  Workload-Name oder {@code null}
 * @param containerNames        explizite Containerauswahl
 * @param allWorkloadContainers ausdrueckliche lokale Policy fuer alle Container des Workloads
 */
public record WorkloadDefinition(WorkloadKind kind, String name, List<String> containerNames,
        boolean allWorkloadContainers) {

    public WorkloadDefinition {
        containerNames = containerNames == null ? List.of() : List.copyOf(containerNames);
    }

    /** In V1 unterstuetzte Workload-Arten. */
    public enum WorkloadKind {
        DEPLOYMENT("Deployment"),
        DEPLOYMENT_CONFIG("DeploymentConfig");

        private final String kubernetesKind;

        WorkloadKind(String kubernetesKind) {
            this.kubernetesKind = kubernetesKind;
        }

        public String kubernetesKind() {
            return kubernetesKind;
        }

        public static WorkloadKind fromKubernetesKind(String value) {
            for (WorkloadKind kind : values()) {
                if (kind.kubernetesKind.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return null;
        }

        public static WorkloadKind fromConfigValue(String value) {
            if (value == null) {
                return null;
            }
            for (WorkloadKind kind : values()) {
                if (kind.name().equalsIgnoreCase(value) || kind.kubernetesKind.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Nicht unterstuetzte Workload-Art: " + value);
        }
    }

    public List<String> missingFields() {
        List<String> missing = new java.util.ArrayList<>();
        if (kind == null) {
            missing.add("workload.kind");
        }
        if (name == null || name.isBlank()) {
            missing.add("workload.name");
        }
        return missing;
    }

    public boolean hasExplicitContainerSelection() {
        return !containerNames.isEmpty();
    }

    public boolean logsAllowedForAnyContainer() {
        return hasExplicitContainerSelection() || allWorkloadContainers;
    }

    public Map<String, Object> toPublicMap() {
        return Map.of(
                "kind", kind == null ? "" : kind.kubernetesKind(),
                "name", name == null ? "" : name,
                "containerNames", containerNames,
                "allWorkloadContainers", allWorkloadContainers);
    }
}
