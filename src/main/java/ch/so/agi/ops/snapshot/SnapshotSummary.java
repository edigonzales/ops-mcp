package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;

import java.time.Instant;
import java.util.List;

/**
 * Zusammenfassung eines gespeicherten Snapshots ohne Logtexte.
 *
 * @param snapshotId       Snapshot-ID
 * @param applicationId    Anwendung
 * @param environment      Umgebung
 * @param useCase          Verwendungszweck
 * @param dataMode         fixture oder live
 * @param collectionStatus Sammelstatus
 * @param collectionStartedAt  Beginn der Erfassung
 * @param collectionFinishedAt Ende der Erfassung
 * @param window           Zeitfenster der Untersuchung
 * @param buildNumber      konkrete Buildnummer
 * @param jobPath          Jobpfadsegmente
 * @param jenkinsInstance  Jenkins-Kennung
 * @param namespace        Namespace
 * @param workloadKind     Workload-Art
 * @param workloadName     Workload-Name
 * @param sources          Quellenstatus
 * @param links            Beziehungen mit Linkstaerke
 * @param evidenceIndex    Evidence-Index
 * @param evidenceCount    Anzahl der Evidence-Dateien
 * @param warnings         Warnungen
 * @param errors           redigierte Fehler
 */
public record SnapshotSummary(
        String snapshotId,
        String applicationId,
        String environment,
        String useCase,
        DataMode dataMode,
        CollectionStatus collectionStatus,
        Instant collectionStartedAt,
        Instant collectionFinishedAt,
        InvestigationWindow window,
        int buildNumber,
        List<String> jobPath,
        String jenkinsInstance,
        String namespace,
        String workloadKind,
        String workloadName,
        List<SourceSummary> sources,
        List<SnapshotLink> links,
        List<EvidenceIndexEntry> evidenceIndex,
        int evidenceCount,
        List<String> warnings,
        List<ch.so.agi.ops.domain.OpsError> errors) {

    public SnapshotSummary {
        jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
        sources = sources == null ? List.of() : List.copyOf(sources);
        links = links == null ? List.of() : List.copyOf(links);
        evidenceIndex = evidenceIndex == null ? List.of() : List.copyOf(evidenceIndex);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static SnapshotSummary from(SnapshotManifest manifest) {
        return new SnapshotSummary(
                manifest.snapshotId(),
                manifest.applicationId(),
                manifest.environment(),
                manifest.useCase(),
                manifest.dataMode(),
                manifest.collectionStatus(),
                manifest.collectionStartedAt(),
                manifest.collectionFinishedAt(),
                manifest.investigationWindow(),
                manifest.buildRef() == null ? 0 : manifest.buildRef().buildNumber(),
                manifest.buildRef() == null ? List.of() : manifest.buildRef().jobPath(),
                manifest.buildRef() == null ? null : manifest.buildRef().instanceId(),
                manifest.namespace(),
                manifest.workloadKind(),
                manifest.workloadName(),
                manifest.sourceResults(),
                manifest.links(),
                manifest.evidenceIndex(),
                manifest.evidenceIndex().size(),
                manifest.warnings(),
                manifest.errors());
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("snapshotId", snapshotId);
        map.put("applicationId", applicationId);
        map.put("environment", environment);
        map.put("useCase", useCase);
        map.put("dataMode", dataMode.wireName());
        map.put("collectionStatus", collectionStatus.wireName());
        map.put("collectionStartedAt", collectionStartedAt == null ? null : collectionStartedAt.toString());
        map.put("collectionFinishedAt", collectionFinishedAt == null ? null : collectionFinishedAt.toString());
        map.put("investigationWindow", window == null ? null : window.toMap());
        // Eine einheitliche Form fuer ops_collect_snapshot, ops_get_snapshot und inspect:
        // buildRef und target als Objekte, zusaetzlich die flachen Felder.
        java.util.Map<String, Object> build = new java.util.LinkedHashMap<>();
        build.put("instanceId", jenkinsInstance);
        build.put("jobPath", jobPath);
        build.put("buildNumber", buildNumber);
        map.put("buildRef", build);
        map.put("jenkinsInstance", jenkinsInstance);
        map.put("jobPath", jobPath);
        map.put("buildNumber", buildNumber);
        java.util.Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("namespace", namespace);
        target.put("workloadKind", workloadKind);
        target.put("workloadName", workloadName);
        map.put("target", target);
        map.put("namespace", namespace);
        map.put("workloadKind", workloadKind);
        map.put("workloadName", workloadName);
        map.put("sourceResults", sources.stream().map(SourceSummary::toMap).toList());
        map.put("links", links.stream().map(SnapshotLink::toMap).toList());
        map.put("evidenceIndex", evidenceIndex.stream().map(EvidenceIndexEntry::toMap).toList());
        if (!warnings.isEmpty()) {
            map.put("warnings", warnings);
        }
        if (!errors.isEmpty()) {
            map.put("errors", errors.stream().map(SourceSummary::errorToMap).toList());
        }
        return map;
    }
}
