package ch.so.agi.ops.snapshot;

import java.util.Map;

/**
 * Ergebnis des Speicherns eines Snapshots.
 *
 * <p>Der Store vergibt die endgueltigen Evidence-IDs ({@code EV-0001}, …). Damit die
 * Werkzeug- und CLI-Ausgaben dieselben IDs nennen wie das Manifest, liefert der Store die
 * Zuordnung von den Roh-IDs des Collectors auf die gespeicherten IDs mit.</p>
 *
 * @param snapshotId gespeicherte Snapshot-ID
 * @param evidenceIdMapping Roh-ID des Collectors zu endgueltiger Evidence-ID
 */
public record SnapshotSaveResult(SnapshotId snapshotId, Map<String, String> evidenceIdMapping) {

    public SnapshotSaveResult {
        evidenceIdMapping = evidenceIdMapping == null ? Map.of() : Map.copyOf(evidenceIdMapping);
    }

    public String evidenceId(String rawId) {
        return evidenceIdMapping.get(rawId);
    }
}
