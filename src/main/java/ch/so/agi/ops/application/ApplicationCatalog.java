package ch.so.agi.ops.application;

import ch.so.agi.ops.config.ApplicationDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.OpsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Listet die konfigurierten Anwendungen und Umgebungen mit ihrem Konfigurationsstatus.
 * Es werden keine Secrets, lokalen Credentialpfade oder die vollstaendige Systemkonfiguration
 * ausgegeben.
 */
public final class ApplicationCatalog {

    private final OpsConfig config;

    public ApplicationCatalog(OpsConfig config) {
        this.config = config;
    }

    /** Alle Anwendungen. */
    public List<ApplicationView> list() {
        List<ApplicationView> result = new ArrayList<>();
        for (ApplicationDefinition application : config.applications().values()) {
            result.add(view(application));
        }
        result.sort(java.util.Comparator.comparing(ApplicationView::applicationId));
        return List.copyOf(result);
    }

    /** Eine einzelne Anwendung oder {@code null}. */
    public ApplicationView find(String applicationId) {
        ApplicationDefinition application = config.application(applicationId);
        return application == null ? null : view(application);
    }

    public List<String> applicationIds() {
        return config.knownApplicationIds();
    }

    private ApplicationView view(ApplicationDefinition application) {
        List<EnvironmentView> environments = new ArrayList<>();
        for (Map.Entry<String, EnvironmentDefinition> entry : application.environments().entrySet()) {
            EnvironmentDefinition definition = entry.getValue();
            List<String> missing = definition.missingFields();
            List<String> warnings = new ArrayList<>();
            if (definition.workload() != null && !definition.workload().logsAllowedForAnyContainer()) {
                warnings.add("Keine Containerauswahl konfiguriert: Containermetadaten werden erfasst, "
                        + "Container-Logs nur nach ausdruecklicher Auswahl oder dokumentierter "
                        + "allWorkloadContainers-Policy.");
            }
            if (definition.deployment() != null && definition.deployment().jobPath().isEmpty()) {
                warnings.add("Kein Jenkins-Jobpfad konfiguriert.");
            }
            environments.add(new EnvironmentView(entry.getKey(), definition.cluster(), definition.namespace(),
                    definition.workload() == null || definition.workload().kind() == null
                            ? null
                            : definition.workload().kind().kubernetesKind(),
                    definition.workload() == null ? null : definition.workload().name(),
                    definition.deployment() == null ? null : definition.deployment().jenkins(),
                    definition.deployment() == null ? List.of() : definition.deployment().jobPath(),
                    definition.configuration() == null ? null : definition.configuration().repository(),
                    missing.isEmpty() ? "ready" : "incomplete",
                    missing,
                    warnings));
        }
        environments.sort(java.util.Comparator.comparing(EnvironmentView::environment));
        return new ApplicationView(application.id(), application.displayName(), environments,
                config.mode().wireName(), config.isLive());
    }

    /**
     * Sicht auf eine Anwendung.
     *
     * @param applicationId Anwendungskennung
     * @param displayName   Anzeigename
     * @param environments  Umgebungen mit Konfigurationsstatus
     * @param dataMode      fixture oder live
     * @param liveMode      true, wenn der Modus live ist
     */
    public record ApplicationView(String applicationId, String displayName, List<EnvironmentView> environments,
            String dataMode, boolean liveMode) {

        public ApplicationView {
            environments = environments == null ? List.of() : List.copyOf(environments);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("applicationId", applicationId);
            map.put("displayName", displayName);
            map.put("dataMode", dataMode);
            map.put("environments", environments.stream().map(EnvironmentView::toMap).toList());
            return map;
        }
    }

    /**
     * Sicht auf eine Umgebung.
     *
     * @param environment     Umgebungsname
     * @param cluster         Clusterkennung
     * @param namespace       Namespace oder {@code null}
     * @param workloadKind    Workload-Art oder {@code null}
     * @param workloadName    Workload-Name oder {@code null}
     * @param jenkinsInstance Jenkins-Kennung oder {@code null}
     * @param jobPath         Jobpfadsegmente
     * @param repository      Repositorykennung oder {@code null}
     * @param configurationStatus {@code ready} oder {@code incomplete}
     * @param missingFields   fehlende Pflichtangaben
     * @param warnings        Hinweise
     */
    public record EnvironmentView(String environment, String cluster, String namespace, String workloadKind,
            String workloadName, String jenkinsInstance, List<String> jobPath, String repository,
            String configurationStatus, List<String> missingFields, List<String> warnings) {

        public EnvironmentView {
            jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("environment", environment);
            map.put("cluster", cluster);
            map.put("namespace", namespace);
            map.put("workloadKind", workloadKind);
            map.put("workloadName", workloadName);
            map.put("jenkins", jenkinsInstance);
            map.put("jobPath", jobPath);
            map.put("repository", repository);
            map.put("configurationStatus", configurationStatus);
            if (!missingFields.isEmpty()) {
                map.put("missingFields", missingFields);
            }
            if (!warnings.isEmpty()) {
                map.put("warnings", warnings);
            }
            return map;
        }
    }
}
