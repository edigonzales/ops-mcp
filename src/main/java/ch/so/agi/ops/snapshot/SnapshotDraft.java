package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.domain.SourceStatus;

import java.time.Instant;
import java.util.List;

/**
 * Fertig erfasster, noch nicht gespeicherter Snapshot. Der Store redigiert erneut,
 * nummeriert die Evidence-Dateien und schreibt atomar.
 *
 * @param applicationId        bestaetigte Anwendung
 * @param environment          bestaetigte Umgebung
 * @param dataMode             fixture oder live
 * @param buildRef             Jenkins-ID, Jobsegmente, konkrete Nummer
 * @param buildMetadataAvailable true, wenn strukturierte Buildmetadaten vorlagen
 * @param clusterId            Clusterkennung
 * @param clusterServer        erwartete API-Serveradresse
 * @param clusterContext       verwendeter Context
 * @param namespace            Namespace
 * @param workloadKind         Workload-Art
 * @param workloadName         Workload-Name
 * @param collectionStartedAt  Beginn der Erfassung
 * @param collectionFinishedAt Ende der Erfassung
 * @param investigationWindow  fachliches Zeitfenster
 * @param collectionStatus     complete, partial oder failed
 * @param sourceResults        Ergebnis je geplanter Quelle
 * @param links                Beziehungen zwischen den Systemen
 * @param evidence             Rohbelege vor der Persistenz
 * @param normalizedData       strukturierte Projektion fuer {@code ops_get_snapshot}
 * @param toolVersions         Versionen der verwendeten Werkzeuge
 * @param warnings             Warnungen des Auftrags
 * @param errors               redigierte Fehler des Auftrags
 */
public record SnapshotDraft(
        String applicationId,
        String environment,
        DataMode dataMode,
        ch.so.agi.ops.domain.jenkins.BuildRef buildRef,
        boolean buildMetadataAvailable,
        String clusterId,
        String clusterServer,
        String clusterContext,
        String namespace,
        String workloadKind,
        String workloadName,
        Instant collectionStartedAt,
        Instant collectionFinishedAt,
        InvestigationWindow investigationWindow,
        CollectionStatus collectionStatus,
        List<SourceSummary> sourceResults,
        List<SnapshotLink> links,
        List<RawEvidence> evidence,
        java.util.Map<String, Object> normalizedData,
        java.util.Map<String, String> toolVersions,
        List<String> warnings,
        List<OpsError> errors) {

    public SnapshotDraft {
        sourceResults = sourceResults == null ? List.of() : List.copyOf(sourceResults);
        links = links == null ? List.of() : List.copyOf(links);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        normalizedData = normalizedData == null ? java.util.Map.of() : java.util.Map.copyOf(normalizedData);
        toolVersions = toolVersions == null ? java.util.Map.of() : java.util.Map.copyOf(toolVersions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public SourceStatus statusOf(String sourceId) {
        return sourceResults.stream()
                .filter(s -> s.sourceId().equals(sourceId))
                .map(SourceSummary::status)
                .findFirst()
                .orElse(SourceStatus.NOT_ATTEMPTED);
    }
}
