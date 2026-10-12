package ch.so.agi.ops.snapshot;

import tools.jackson.databind.JsonNode;

/**
 * Gelesener Snapshot: Manifest plus normalisierte Projektion. Beim erneuten Lesen gelten
 * Manifest- und Evidence-Dateien als nicht vertrauenswuerdig; Pfadgrenzen, Groessen und
 * Hashes werden geprueft.
 *
 * @param manifest   Manifest des Snapshots
 * @param normalized strukturierte Projektion
 * @param rootPath   lokaler Wurzelpfad des Snapshotverzeichnisses
 */
public record StoredSnapshot(SnapshotManifest manifest, JsonNode normalized, java.nio.file.Path rootPath) {

    public SnapshotSummary summary() {
        return SnapshotSummary.from(manifest);
    }

    /** Prueft, ob eine Evidence-ID zu diesem Snapshot gehoert. */
    public boolean hasEvidence(String evidenceId) {
        return manifest.hasEvidence(evidenceId);
    }

    public EvidenceIndexEntry evidence(String evidenceId) {
        return manifest.evidence(evidenceId);
    }
}
