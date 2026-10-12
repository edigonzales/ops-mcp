package ch.so.agi.ops.security;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.evidence.FileSnapshotStore;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.snapshot.EvidenceIndexEntry;
import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import ch.so.agi.ops.util.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistenz – atomarer Snapshot, unveraenderliche Evidence und erkannter Korruption.
 *
 * <p>Belegt: Nach {@code save} existiert nur das finale Verzeichnis; ein simulierter Abbruch
 * wird nicht als Snapshot gelistet; eine nachtraegliche Aenderung an einer Evidence-Datei wird
 * ueber den Hash erkannt; ein korrupter Snapshot (fehlendes oder widerspruechliches Manifest)
 * ergibt {@code ARTIFACT_INTEGRITY}. Die Referenz- und Ueberschreibungsregeln fuer Berichte
 * stehen in {@link ReportAbuseAcceptanceTest} (S10).</p>
 */
class SnapshotPersistenceAcceptanceTest {

    @TempDir
    Path tempDir;

    private AcceptanceSupport.Harness harness;
    private SnapshotId id;

    private void neuerSnapshot() {
        harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        id = harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(5, "container"))));
    }

    private Path snapshotVerzeichnis() {
        return tempDir.resolve(FileSnapshotStore.SNAPSHOTS_DIR).resolve(id.value());
    }

    private static void patchManifest(Path snapshotVerzeichnis, Consumer<ObjectNode> patch) {
        Path manifest = snapshotVerzeichnis.resolve("manifest.json");
        ObjectNode root = (ObjectNode) YamlSupport.jsonMapper().readTree(AcceptanceSupport.readString(manifest));
        patch.accept(root);
        AcceptanceSupport.writeString(manifest, root.toString());
    }

    @Test
    @DisplayName("Persistenz: Snapshot ist atomar fertig und hinterlaesst kein .incomplete-Verzeichnis")
    void snapshotIstAtomar() throws IOException {
        neuerSnapshot();

        assertTrue(harness.snapshots().exists(id));
        assertTrue(Files.isRegularFile(snapshotVerzeichnis().resolve("manifest.json")));
        assertTrue(Files.isRegularFile(snapshotVerzeichnis().resolve("normalized.json")));
        assertTrue(Files.isRegularFile(snapshotVerzeichnis().resolve("evidence/EV-0001.txt")));

        Path snapshots = tempDir.resolve(FileSnapshotStore.SNAPSHOTS_DIR);
        try (Stream<Path> stream = Files.list(snapshots)) {
            List<String> reste = stream.map(pfad -> pfad.getFileName().toString())
                    .filter(name -> name.startsWith(".")).toList();
            assertEquals(List.of(), reste, "Nach einem erfolgreichen save darf kein Zwischenstand bleiben.");
        }
        assertEquals(List.of(id), harness.snapshots().list());
        assertEquals(1, harness.snapshots().list().size());
    }

    @Test
    @DisplayName("Persistenz: ein simulierter Abbruch (.incomplete-xyz) wird nicht als Snapshot gelistet")
    void abgebrochenerSnapshotWirdNichtGelistet() throws IOException {
        neuerSnapshot();
        Path abbruch = tempDir.resolve(FileSnapshotStore.SNAPSHOTS_DIR).resolve(".incomplete-xyz");
        Files.createDirectories(abbruch.resolve("evidence"));
        AcceptanceSupport.writeString(abbruch.resolve("manifest.json"), "{\"schemaVersion\":1}");

        List<SnapshotId> gelistet = harness.snapshots().list();

        assertEquals(List.of(id), gelistet);
        assertTrue(harness.snapshots().exists(id));
        assertThrows(IllegalArgumentException.class, () -> SnapshotId.of(".incomplete-xyz"));
    }

    @Test
    @DisplayName("Persistenz: eine nachtraegliche Aenderung an evidence/EV-0001.txt ergibt ARTIFACT_INTEGRITY")
    void nachtraeglicheAenderungWirdErkannt() {
        neuerSnapshot();
        Path evidence = snapshotVerzeichnis().resolve("evidence/EV-0001.txt");
        String original = AcceptanceSupport.readString(evidence);
        String geaendert = (original.charAt(0) == 'X' ? "Y" : "X") + original.substring(1);
        assertEquals(original.length(), geaendert.length(), "Der Test aendert die Datei bei gleicher Groesse.");
        AcceptanceSupport.writeString(evidence, geaendert);

        ArtifactIntegrityException beimLesen = assertThrows(ArtifactIntegrityException.class,
                () -> harness.snapshots().read(id));
        assertEquals(ErrorCode.ARTIFACT_INTEGRITY, beimLesen.code());

        ArtifactIntegrityException beimEvidenceLesen = assertThrows(ArtifactIntegrityException.class,
                () -> harness.snapshots().readEvidence(id, "EV-0001", 1, 10));
        assertEquals(ErrorCode.ARTIFACT_INTEGRITY, beimEvidenceLesen.code());
    }

    @Test
    @DisplayName("Persistenz: geloeschtes Manifest ergibt ARTIFACT_INTEGRITY")
    void geloeschtesManifestWirdErkannt() throws IOException {
        neuerSnapshot();
        Files.delete(snapshotVerzeichnis().resolve("manifest.json"));

        ArtifactIntegrityException error = assertThrows(ArtifactIntegrityException.class,
                () -> harness.snapshots().read(id));

        assertEquals(ErrorCode.ARTIFACT_INTEGRITY, error.code());
    }

    @Test
    @DisplayName("Persistenz: ungueltige Snapshot-ID im Manifest ergibt ARTIFACT_INTEGRITY")
    void ungueltigeSnapshotIdImManifestWirdErkannt() {
        neuerSnapshot();
        patchManifest(snapshotVerzeichnis(), root -> root.put("snapshotId", "20261002T100000Z-efgh5678"));

        ArtifactIntegrityException error = assertThrows(ArtifactIntegrityException.class,
                () -> harness.snapshots().read(id));

        assertEquals(ErrorCode.ARTIFACT_INTEGRITY, error.code());
    }

    @Test
    @DisplayName("Persistenz: Hash und Zeilenreferenzen bleiben stabil")
    void hashUndZeilenreferenzenBleibenStabil() throws IOException {
        neuerSnapshot();
        StoredSnapshot snapshot = harness.read(id);
        EvidenceIndexEntry eintrag = AcceptanceSupport.evidenceEntry(snapshot, "EV-0001");
        Path evidence = snapshotVerzeichnis().resolve(eintrag.path());

        assertEquals(Hashes.sha256Hex(evidence), eintrag.sha256());
        assertEquals(Files.size(evidence), eintrag.byteSize());
        assertEquals(5, eintrag.lineCount());
        assertTrue(eintrag.path().startsWith("evidence/"));

        EvidencePage ersteSeite = harness.snapshots().readEvidence(id, "EV-0001", 1, 10);
        EvidencePage zweiteSeite = harness.snapshots().readEvidence(id, "EV-0001", 1, 10);
        assertEquals(ersteSeite, zweiteSeite, "Zweimal gelesen muss dieselbe Seite ergeben.");
        assertEquals("container Zeile 1", ersteSeite.lines().getFirst());
    }
}
