package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceStatus;

import java.util.List;
import java.util.Map;

/**
 * Zusammenfassung des Ergebnisses einer einzelnen geplanten Quelle im Manifest.
 * Jede Quelle berichtet explizit, ob Daten erhoben, nicht vorhanden, nicht anwendbar,
 * nicht erlaubt, abgeschnitten oder technisch nicht erreichbar sind.
 *
 * @param sourceId    Kennung der Quelle
 * @param status      Erfassungsstatus
 * @param collectedAt Erfassungszeit
 * @param evidenceIds erzeugte Evidence-IDs
 * @param notes       Hinweise
 * @param errors      redigierte Fehler
 */
public record SourceSummary(
        String sourceId,
        SourceStatus status,
        java.time.Instant collectedAt,
        List<String> evidenceIds,
        List<String> notes,
        List<OpsError> errors) {

    public SourceSummary {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        notes = notes == null ? List.of() : List.copyOf(notes);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean isGap() {
        return status.isGap();
    }

    /**
     * Der Sammelstatus eines Snapshots bleibt {@code COMPLETE} nur, wenn keine geplante Quelle
     * eine technische Luecke meldet. {@code NOT_PRESENT} und {@code NOT_APPLICABLE} sind
     * regulaere fachliche Ergebnisse und keine technische Luecke.
     */
    public static CollectionStatus aggregate(CollectionStatus current, List<SourceSummary> summaries) {
        CollectionStatus result = current;
        for (SourceSummary summary : summaries) {
            if (summary.isGap() && result == CollectionStatus.COMPLETE) {
                result = CollectionStatus.PARTIAL;
            }
        }
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sourceId", sourceId);
        map.put("status", status.name());
        map.put("collectedAt", collectedAt == null ? null : collectedAt.toString());
        map.put("evidenceCount", evidenceIds.size());
        map.put("evidenceIds", evidenceIds);
        if (!notes.isEmpty()) {
            map.put("notes", notes);
        }
        if (!errors.isEmpty()) {
            map.put("errors", errors.stream().map(SourceSummary::errorToMap).toList());
        }
        return map;
    }

    public static Map<String, Object> errorToMap(OpsError error) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("code", error.code().name());
        map.put("sourceId", error.sourceId());
        map.put("message", error.message());
        map.put("retryable", error.retryable());
        if (!error.suggestedAction().isBlank()) {
            map.put("suggestedAction", error.suggestedAction());
        }
        return map;
    }
}
