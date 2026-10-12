package ch.so.agi.ops.snapshot;

/**
 * Verweis auf eine Datei im Snapshotverzeichnis. Alle Pfade sind relative, portable Pfade
 * innerhalb des Snapshot-Roots; absolute Pfade und {@code ..} sind unzulaessig.
 */
public record ArtifactRef(String relativePath, String sha256, long byteSize, int lineCount) {

    public ArtifactRef {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativer Pfad fehlt");
        }
        if (relativePath.startsWith("/") || relativePath.contains("..")) {
            throw new IllegalArgumentException("unzulaessiger relativer Pfad: " + relativePath);
        }
    }
}
