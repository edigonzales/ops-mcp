package ch.so.agi.ops.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auftragsweites Lesebudget fuer Evidence-Antworten.
 *
 * <p>Ein Agent darf durch wiederholte Aufrufe von {@code ops_read_evidence} keinen
 * unbeschraenkten Zugriff auf weitere Quellen eroeffnen. Neben dem Antwortbudget je Aufruf
 * gilt deshalb ein Gesamtbudget je geoeffnetem Snapshot.</p>
 */
public final class EvidenceReadBudget {

    private final Map<String, Integer> consumedBySnapshot = new ConcurrentHashMap<>();
    private final int perSnapshotLimit;

    public EvidenceReadBudget(int perSnapshotLimit) {
        this.perSnapshotLimit = perSnapshotLimit <= 0 ? 32 * 1024 : perSnapshotLimit;
    }

    public int perSnapshotLimit() {
        return perSnapshotLimit;
    }

    public int consumed(String snapshotId) {
        return consumedBySnapshot.getOrDefault(snapshotId, 0);
    }

    public int remaining(String snapshotId) {
        return Math.max(0, perSnapshotLimit - consumed(snapshotId));
    }

    public boolean exhausted(String snapshotId) {
        return remaining(snapshotId) <= 0;
    }

    /** Verbucht eine Antwort. Liefert false, wenn das Gesamtbudget ueberschritten wurde. */
    public synchronized boolean consume(String snapshotId, int bytes) {
        int next = consumed(snapshotId) + Math.max(0, bytes);
        consumedBySnapshot.put(snapshotId, next);
        return next <= perSnapshotLimit;
    }

    /** Setzt das Budget fuer einen Snapshot zurueck; wird beim Sammeln eines neuen Snapshots genutzt. */
    public void reset(String snapshotId) {
        consumedBySnapshot.remove(snapshotId);
    }

    public Map<String, Object> describe(String snapshotId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("consumedBytes", consumed(snapshotId));
        map.put("limitBytes", perSnapshotLimit);
        map.put("remainingBytes", remaining(snapshotId));
        map.put("exhausted", exhausted(snapshotId));
        return map;
    }
}
