package ch.so.agi.ops.report;

import java.time.Instant;

/**
 * Gespeicherter Bericht. Der Ablageort ist immer {@code reports/<snapshotId>/<reportId>/};
 * ein beliebiger Dateipfad ist nicht zulaessig.
 *
 * @param reportId       Kennung des Berichts
 * @param snapshotId     gebundener Snapshot
 * @param jsonPath       relativer Pfad der JSON-Datei
 * @param markdownPath   relativer Pfad der Markdown-Datei
 * @param jsonSha256     Hash der JSON-Datei
 * @param markdownSha256 Hash der Markdown-Datei
 * @param createdAt      Speicherzeit
 * @param validation     Ergebnis der Validierung
 */
public record StoredReport(
        String reportId,
        String snapshotId,
        String jsonPath,
        String markdownPath,
        String jsonSha256,
        String markdownSha256,
        Instant createdAt,
        ReportValidationResult validation) {

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("reportId", reportId);
        map.put("snapshotId", snapshotId);
        map.put("jsonPath", jsonPath);
        map.put("markdownPath", markdownPath);
        map.put("createdAt", createdAt == null ? null : createdAt.toString());
        map.put("valid", validation.valid());
        if (!validation.warnings().isEmpty()) {
            map.put("warnings", validation.warnings());
        }
        if (!validation.errors().isEmpty()) {
            map.put("errors", validation.errors().stream()
                    .map(error -> java.util.Map.of("code", error.code().name(), "field", error.sourceId(),
                            "message", error.message()))
                    .toList());
        }
        return map;
    }
}
