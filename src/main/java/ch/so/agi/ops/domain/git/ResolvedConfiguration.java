package ch.so.agi.ops.domain.git;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aufgeloeste Konfiguration eines Workload-Templates an einem belegten Commit.
 *
 * @param repositoryId         Repositorykennung
 * @param localPath            lokaler Checkout, wie er in der Konfiguration angegeben ist
 * @param revision             Revisionsherkunft inklusive Stufe
 * @param containerNames       Container, die in den erlaubten Dateien gefunden wurden
 * @param workloads            Workload-Art -> Containerkonfiguration
 * @param files                Ergebnis je geplanter Datei
 * @param status               Gesamtstatus der Konfigurationserfassung
 * @param rolloutTimeParameters ausgewaehlte Rollout-Zeitparameter (z. B. progressDeadlineSeconds)
 * @param currentReferenceFiles zusaetzlich gelesene Dateien des heutigen Stands; ausdruecklich nur Referenz
 */
public record ResolvedConfiguration(
        String repositoryId,
        String localPath,
        RevisionProvenance revision,
        List<String> containerNames,
        Map<String, ContainerConfig> workloads,
        List<FileReadResult> files,
        ConfigStatus status,
        Map<String, ConfigValue> rolloutTimeParameters,
        List<String> currentReferenceFiles) {

    public enum ConfigStatus {
        /** Alle geplanten Dateien an der belegten Revision gelesen und ausgewertet. */
        RESOLVED,
        /** Teilweise gelesen; mindestens eine Datei fehlt, ist nicht erlaubt oder nicht lesbar. */
        PARTIAL,
        /** Keine verwertbare Konfiguration; insbesondere fehlt die Revision. */
        UNAVAILABLE
    }

    public ResolvedConfiguration {
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(revision, "revision");
        containerNames = containerNames == null ? List.of() : List.copyOf(containerNames);
        workloads = workloads == null ? Map.of() : Map.copyOf(workloads);
        files = files == null ? List.of() : List.copyOf(files);
        Objects.requireNonNull(status, "status");
        rolloutTimeParameters = rolloutTimeParameters == null ? Map.of() : Map.copyOf(rolloutTimeParameters);
        currentReferenceFiles = currentReferenceFiles == null ? List.of() : List.copyOf(currentReferenceFiles);
    }

    public boolean revisionUnavailable() {
        return !revision.hasCommit();
    }

    public static ResolvedConfiguration unavailable(String repositoryId, String localPath,
            RevisionProvenance revision, List<FileReadResult> files) {
        return new ResolvedConfiguration(repositoryId, localPath, revision, List.of(), Map.of(), files,
                ConfigStatus.UNAVAILABLE, Map.of(), List.of());
    }
}
