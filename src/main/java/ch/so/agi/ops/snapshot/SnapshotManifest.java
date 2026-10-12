package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Manifest eines Snapshots. Pflichtfelder laut SCHNITTSTELLEN.md Abschnitt 5.2.
 *
 * <p>{@code complete} bedeutet: Die geplante begrenzte Erfassung wurde ohne technische Luecke
 * abgeschlossen. Es bedeutet <em>nicht</em>: Historie vollstaendig vorhanden oder Ursache bewiesen.</p>
 *
 * @param schemaVersion         immer {@code 1}
 * @param snapshotId            zufaellig erzeugte, pfadsichere ID
 * @param useCase               in V1 {@code deployment-failure}
 * @param dataMode              fixture oder live; wird nie weggelassen
 * @param applicationId         bestaetigte Anwendung
 * @param environment           bestaetigte Umgebung
 * @param buildRef              Jenkins-ID, Jobsegmente, konkrete Nummer
 * @param clusterId             Kennung des Clusters aus der Konfiguration
 * @param namespace             erlaubter Namespace
 * @param workloadKind          Workload-Art
 * @param workloadName          Workload-Name
 * @param collectionStartedAt   Beginn der Erfassung
 * @param collectionFinishedAt  Ende der Erfassung
 * @param investigationWindow   fachliches Zeitfenster und Herkunft
 * @param collectionStatus      complete, partial oder failed
 * @param sourceResults         Status je geplantem Quellzugriff
 * @param links                 Beziehungen zwischen den Systemen
 * @param evidenceIndex         Evidence-ID, Art, Herkunft, Pfad, Hash, Zeilenanzahl
 * @param redaction             Regelversion, Zaehler und Einschraenkungen
 * @param toolVersions          Java, Anwendung, oc/Git soweit verwendet
 * @param warnings              gesammelte Warnungen des Auftrags
 * @param errors                gesammelte redigierte Fehler des Auftrags
 */
public record SnapshotManifest(
        int schemaVersion,
        String snapshotId,
        String useCase,
        DataMode dataMode,
        String applicationId,
        String environment,
        BuildRefView buildRef,
        String clusterId,
        String namespace,
        String workloadKind,
        String workloadName,
        Instant collectionStartedAt,
        Instant collectionFinishedAt,
        InvestigationWindow investigationWindow,
        CollectionStatus collectionStatus,
        List<SourceSummary> sourceResults,
        List<SnapshotLink> links,
        List<EvidenceIndexEntry> evidenceIndex,
        RedactionSummary redaction,
        Map<String, String> toolVersions,
        List<String> warnings,
        List<OpsError> errors) {

    public static final int SCHEMA_VERSION = 1;
    public static final String USE_CASE_DEPLOYMENT_FAILURE = "deployment-failure";

    public SnapshotManifest {
        sourceResults = sourceResults == null ? List.of() : List.copyOf(sourceResults);
        links = links == null ? List.of() : List.copyOf(links);
        evidenceIndex = evidenceIndex == null ? List.of() : List.copyOf(evidenceIndex);
        toolVersions = toolVersions == null ? Map.of() : Map.copyOf(toolVersions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Jenkins-Bezug im Manifest. */
    public record BuildRefView(
            String instanceId,
            List<String> jobPath,
            int buildNumber,
            boolean metadataAvailable) {

        public BuildRefView {
            jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("instanceId", instanceId);
            map.put("jobPath", jobPath);
            map.put("buildNumber", buildNumber);
            map.put("metadataAvailable", metadataAvailable);
            return map;
        }
    }

    public EvidenceIndexEntry evidence(String evidenceId) {
        return evidenceIndex.stream().filter(e -> e.id().equals(evidenceId)).findFirst().orElse(null);
    }

    public boolean hasEvidence(String evidenceId) {
        return evidence(evidenceId) != null;
    }

    public EvidenceType evidenceType(String evidenceId) {
        EvidenceIndexEntry entry = evidence(evidenceId);
        return entry == null ? null : entry.type();
    }

    public boolean isComplete() {
        return collectionStatus == CollectionStatus.COMPLETE;
    }

    public List<SourceSummary> gaps() {
        return sourceResults.stream().filter(SourceSummary::isGap).toList();
    }

    public SourceStatus statusOf(String sourceId) {
        return sourceResults.stream()
                .filter(s -> s.sourceId().equals(sourceId))
                .map(SourceSummary::status)
                .findFirst()
                .orElse(SourceStatus.NOT_ATTEMPTED);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("snapshotId", snapshotId);
        map.put("useCase", useCase);
        map.put("dataMode", dataMode.wireName());
        map.put("applicationId", applicationId);
        map.put("environment", environment);
        map.put("buildRef", buildRef == null ? null : buildRef.toMap());
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("clusterId", clusterId);
        target.put("namespace", namespace);
        target.put("workloadKind", workloadKind);
        target.put("workloadName", workloadName);
        map.put("target", target);
        map.put("collectionStartedAt", collectionStartedAt == null ? null : collectionStartedAt.toString());
        map.put("collectionFinishedAt", collectionFinishedAt == null ? null : collectionFinishedAt.toString());
        map.put("investigationWindow", investigationWindow == null ? null : investigationWindow.toMap());
        map.put("collectionStatus", collectionStatus.wireName());
        map.put("sourceResults", sourceResults.stream().map(SourceSummary::toMap).toList());
        map.put("links", links.stream().map(SnapshotLink::toMap).toList());
        map.put("evidenceIndex", evidenceIndex.stream().map(EvidenceIndexEntry::toMap).toList());
        map.put("redaction", redaction == null ? null : redaction.toMap());
        map.put("toolVersions", toolVersions);
        if (!warnings.isEmpty()) {
            map.put("warnings", warnings);
        }
        if (!errors.isEmpty()) {
            map.put("errors", errors.stream().map(SourceSummary::errorToMap).toList());
        }
        return map;
    }
}
