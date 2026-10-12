package ch.so.agi.ops.application.port;

import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.SnapshotDraft;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.SnapshotSummary;
import ch.so.agi.ops.snapshot.StoredSnapshot;

/**
 * Persistenz lokaler Snapshots. Snapshots werden atomar fertiggestellt; eine unvollstaendige
 * laufende Erfassung ist nicht als finaler Snapshot sichtbar.
 */
public interface SnapshotStore {

    /**
     * Schreibt einen vollstaendigen Snapshot und liefert seine ID samt Zuordnung der
     * vergebenen Evidence-IDs.
     */
    ch.so.agi.ops.snapshot.SnapshotSaveResult save(SnapshotDraft draft);

    /** Liefert die Zusammenfassung eines vorhandenen Snapshots. */
    SnapshotSummary describe(SnapshotId id);

    /** Liest einen vorhandenen Snapshot vollstaendig (Manifest plus normalisierte Daten). */
    StoredSnapshot read(SnapshotId id);

    /** Liest einen begrenzten Ausschnitt einer Evidence-Datei. */
    EvidencePage readEvidence(SnapshotId id, String evidenceId, int startLine, int maxLines);

    /** Wahrheitswert, ob ein Snapshot mit dieser ID final vorhanden ist. */
    boolean exists(SnapshotId id);

    /** Liste der vorhandenen Snapshot-IDs, neueste zuerst. */
    java.util.List<SnapshotId> list();
}
