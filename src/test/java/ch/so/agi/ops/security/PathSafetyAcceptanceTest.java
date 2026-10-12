package ch.so.agi.ops.security;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.snapshot.SnapshotId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S03 – {@code ../}, absoluter Evidencepfad und Symlink-Flucht.
 *
 * <p>Belegt: Pfadgrenzen gelten unabhaengig von der Herkunft der Anfrage; ausserhalb der
 * vorgesehenen Wurzel wird weder gelesen noch geschrieben. Eine manipulierte Evidence-ID
 * oder ein manipulierter Indexpfad fuehrt zu {@code ARTIFACT_INTEGRITY} bzw. {@code NOT_FOUND}.</p>
 */
class PathSafetyAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final List<String> UNSICHER = List.of(
            "../x",
            "/etc/passwd",
            "a/../../b",
            "a/./b",
            "a//b",
            "a\\b",
            "a\u0000b",
            "..",
            ".",
            "x".repeat(600),
            "\\windows\\system32");

    @Test
    @DisplayName("S03: isSafeRelativePath weist Traversal, absolute Pfade, Null-Zeichen und Ueberlaenge ab")
    void isSafeRelativePathWeistUnsicherePfadeAb() {
        for (String pfad : UNSICHER) {
            assertFalse(PathSafety.isSafeRelativePath(pfad),
                    "Pfad muss abgewiesen werden: " + PathSafety.describe(pfad));
        }
        assertFalse(PathSafety.isSafeRelativePath(null));
        assertFalse(PathSafety.isSafeRelativePath(""));
        assertFalse(PathSafety.isSafeRelativePath("   "));

        assertTrue(PathSafety.isSafeRelativePath("evidence/EV-0001.txt"));
        assertTrue(PathSafety.isSafeRelativePath("a/b/c.json"));
        assertTrue(PathSafety.isSafeRelativePath("manifest.json"));
    }

    @Test
    @DisplayName("S03: resolveWithin verlaesst die Wurzel nicht")
    void resolveWithinVerlaesstDieWurzelNicht() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));

        for (String pfad : UNSICHER) {
            ScopeViolationException error = assertThrows(ScopeViolationException.class,
                    () -> PathSafety.resolveWithin(root, pfad),
                    "Pfad muss abgewiesen werden: " + PathSafety.describe(pfad));
            assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        }

        Path aufgeloest = PathSafety.resolveWithin(root, "evidence/EV-0001.txt");
        assertEquals(root.resolve("evidence").resolve("EV-0001.txt"), aufgeloest);
        assertTrue(aufgeloest.startsWith(root));
    }

    @Test
    @DisplayName("S03: resolveWithinNoSymlinkEscape weist einen Symlink aus der Wurzel ab")
    void symlinkFluchtWirdAbgewiesen() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path aussen = Files.createDirectories(tempDir.resolve("aussen"));
        Path geheim = Files.writeString(aussen.resolve("geheim.txt"), "AUSSERHALB-GEHEIM");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, geheim);
        } catch (UnsupportedOperationException | IOException ex) {
            Assumptions.assumeTrue(false, "Symlinks werden auf diesem Dateisystem nicht unterstuetzt: " + ex);
        }

        ScopeViolationException error = assertThrows(ScopeViolationException.class,
                () -> PathSafety.resolveWithinNoSymlinkEscape(root, "link.txt"));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertFalse(error.getMessage().contains("AUSSERHALB-GEHEIM"), "Der Inhalt darf nicht in der Meldung stehen.");

        // Gegenprobe: ein Symlink innerhalb der Wurzel bleibt zulaessig.
        Path ziel = Files.writeString(root.resolve("ziel.txt"), "innerhalb");
        Path innenLink = root.resolve("innen.txt");
        try {
            Files.createSymbolicLink(innenLink, ziel);
        } catch (UnsupportedOperationException | IOException ex) {
            Assumptions.assumeTrue(false, "Symlinks werden auf diesem Dateisystem nicht unterstuetzt: " + ex);
        }
        assertEquals(root.resolve("innen.txt"), PathSafety.resolveWithinNoSymlinkEscape(root, "innen.txt"));
    }

    @Test
    @DisplayName("S03: FileSnapshotStore.readEvidence weist manipulierte Evidence-IDs ab")
    void manipulierteEvidenceIdWirdAbgewiesen() {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        SnapshotId id = harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(3, "container"))));
        AcceptanceSupport.writeString(tempDir.resolve("aussen.txt"), "AUSSERHALB-GEHEIM");

        List<String> manipulationen = List.of("../../../etc/passwd", "../aussen.txt", "aussen.txt", "EV-9999",
                "EV-0001/../../aussen.txt", "EV-0001.txt", "..", "evidence/EV-0001.txt");

        for (String evidenceId : manipulationen) {
            ArtifactIntegrityException error = assertThrows(ArtifactIntegrityException.class,
                    () -> harness.snapshots().readEvidence(id, evidenceId, 1, 50),
                    "Evidence-ID muss abgewiesen werden: " + PathSafety.describe(evidenceId));
            assertTrue(error.code() == ErrorCode.ARTIFACT_INTEGRITY || error.code() == ErrorCode.NOT_FOUND,
                    "Unerwarteter Fehlercode fuer " + evidenceId + ": " + error.code());
            assertFalse(String.valueOf(error.getMessage()).contains("AUSSERHALB-GEHEIM"),
                    "Es darf kein Inhalt ausserhalb der Wurzel gelesen werden.");
        }
    }

    @Test
    @DisplayName("S03: manipulierter Indexpfad ausserhalb des Snapshots ergibt ARTIFACT_INTEGRITY")
    void manipulierterIndexpfadWirdErkannt() {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        SnapshotId id = harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(3, "container"))));
        AcceptanceSupport.writeString(tempDir.resolve("aussen.txt"), "AUSSERHALB-GEHEIM");
        Path manifest = tempDir.resolve("snapshots").resolve(id.value()).resolve("manifest.json");
        ObjectNode root = (ObjectNode) YamlSupport.jsonMapper().readTree(AcceptanceSupport.readString(manifest));
        ObjectNode eintrag = (ObjectNode) root.get("evidenceIndex").get(0);
        ((ObjectNode) eintrag.get("locator")).put("path", "../aussen.txt");
        AcceptanceSupport.writeString(manifest, root.toString());

        ArtifactIntegrityException error = assertThrows(ArtifactIntegrityException.class,
                () -> harness.snapshots().read(id));

        assertEquals(ErrorCode.ARTIFACT_INTEGRITY, error.code());
        assertFalse(String.valueOf(error.getMessage()).contains("AUSSERHALB-GEHEIM"));
    }
}
