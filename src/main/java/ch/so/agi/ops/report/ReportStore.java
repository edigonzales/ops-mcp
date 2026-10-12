package ch.so.agi.ops.report;

/**
 * Persistenz validierter Berichte.
 *
 * <p>Die Validierung prueft Struktur, Snapshotbindung und Belegreferenzen. Ob ein Beleg den
 * Text inhaltlich traegt, bleibt Gegenstand der Agentenabnahme und der menschlichen Pruefung.</p>
 */
public interface ReportStore {

    /**
     * Validiert und speichert einen Bericht.
     *
     * @param snapshot gespeicherter Snapshot, gegen den validiert wird
     * @param document Berichtsentwurf
     * @return gespeicherter Bericht mit relativen Pfaden
     */
    StoredReport save(ch.so.agi.ops.snapshot.StoredSnapshot snapshot, DiagnosisDocument document);

    /** Liest einen gespeicherten Bericht als JSON. */
    DiagnosisDocument read(ch.so.agi.ops.snapshot.SnapshotId snapshotId, String reportId);

    /** Listet die Berichte eines Snapshots. */
    java.util.List<StoredReport> list(ch.so.agi.ops.snapshot.SnapshotId snapshotId);
}
