package ch.so.agi.ops.security;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.report.DiagnosisDocument;
import ch.so.agi.ops.report.FileReportStore;
import ch.so.agi.ops.report.ReportException;
import ch.so.agi.ops.report.ReportStore;
import ch.so.agi.ops.report.StoredReport;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S10 – Bericht setzt {@code executed: true}, manipuliert Evidence-ID, Snapshotbindung oder
 * Ausgabepfad.
 *
 * <p>Belegt: Solche Berichte werden abgewiesen, ohne dass etwas geschrieben wird. Ein
 * Ausgabepfad ist ueber die Schnittstelle nicht setzbar; gespeicherte Pfade beginnen immer mit
 * {@code reports/} und bleiben innerhalb des Ausgabebereichs. Ein vorhandener Bericht wird
 * nicht ueberschrieben.</p>
 */
class ReportAbuseAcceptanceTest {

    @TempDir
    Path tempDir;

    private AcceptanceSupport.Harness harness;
    private StoredSnapshot snapshot;

    @BeforeEach
    void setUp() {
        harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        snapshot = harness.saveAndRead(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(5, "container"))));
    }

    private DiagnosisDocument gueltigerEntwurf() {
        return AcceptanceSupport.validDocument(snapshot.manifest().snapshotId(), "EV-0001", 5);
    }

    private void assertNichtsGeschrieben() {
        assertFalse(Files.exists(tempDir.resolve("reports")),
                "Ein abgewiesener Bericht darf nichts schreiben.");
    }

    @Test
    @DisplayName("S10: executed true wird abgewiesen")
    void executedTrueWirdAbgewiesen() {
        DiagnosisDocument entwurf = new DiagnosisDocument(gueltigerEntwurf().schemaVersion(),
                gueltigerEntwurf().snapshotId(), gueltigerEntwurf().language(), gueltigerEntwurf().assessment(),
                gueltigerEntwurf().title(), gueltigerEntwurf().summary(), gueltigerEntwurf().facts(),
                gueltigerEntwurf().hypotheses(), gueltigerEntwurf().timeline(),
                List.of(new DiagnosisDocument.Recommendation("oc rollout restart deployment/fixture-app",
                        "Synthetische Begruendung.", List.of("H-1"), "Synthetisches Risiko.",
                        "Synthetische Verifikation.", true)),
                gueltigerEntwurf().openQuestions(), gueltigerEntwurf().limitations(),
                gueltigerEntwurf().actionsTaken());

        ReportException error = assertThrows(ReportException.class, () -> harness.reports().save(snapshot, entwurf));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(eintrag -> eintrag.sourceId().contains("executed")),
                error.errors().toString());
        assertNichtsGeschrieben();
    }

    @Test
    @DisplayName("S10: unbekannte Evidence-ID wird abgewiesen")
    void unbekannteEvidenceIdWirdAbgewiesen() {
        DiagnosisDocument entwurf = AcceptanceSupport.validDocument(snapshot.manifest().snapshotId(), "EV-9999", 5);

        ReportException error = assertThrows(ReportException.class, () -> harness.reports().save(snapshot, entwurf));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(eintrag -> eintrag.message().contains("EV-9999")),
                error.errors().toString());
        assertNichtsGeschrieben();
    }

    @Test
    @DisplayName("S10: Snapshot-ID eines fremden Snapshots wird abgewiesen")
    void fremdeSnapshotIdWirdAbgewiesen() {
        DiagnosisDocument entwurf = AcceptanceSupport.validDocument("20261002T100000Z-efgh5678", "EV-0001", 5);

        ReportException error = assertThrows(ReportException.class, () -> harness.reports().save(snapshot, entwurf));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(eintrag -> eintrag.message().contains("Snapshotbindung")),
                error.errors().toString());
        assertNichtsGeschrieben();
    }

    @Test
    @DisplayName("S10: actionsTaken ungleich leer wird abgewiesen")
    void actionsTakenNichtLeerWirdAbgewiesen() {
        DiagnosisDocument basis = gueltigerEntwurf();
        DiagnosisDocument entwurf = new DiagnosisDocument(basis.schemaVersion(), basis.snapshotId(),
                basis.language(), basis.assessment(), basis.title(), basis.summary(), basis.facts(),
                basis.hypotheses(), basis.timeline(), basis.recommendations(), basis.openQuestions(),
                basis.limitations(), List.of("oc delete pod fixture-app-0"));

        ReportException error = assertThrows(ReportException.class, () -> harness.reports().save(snapshot, entwurf));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(eintrag -> "actionsTaken".equals(eintrag.sourceId())),
                error.errors().toString());
        assertNichtsGeschrieben();
    }

    @Test
    @DisplayName("S10: ausfuehrbarer Inhalt (<script>) wird abgewiesen")
    void scriptImTextWirdAbgewiesen() {
        DiagnosisDocument entwurf = AcceptanceSupport.withTexts(gueltigerEntwurf(), "<script>alert(1)</script>",
                "Zusammenfassung.");

        ReportException error = assertThrows(ReportException.class, () -> harness.reports().save(snapshot, entwurf));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(eintrag -> eintrag.message().contains("ausfuehrbaren Inhalt")),
                error.errors().toString());
        assertNichtsGeschrieben();
    }

    @Test
    @DisplayName("S10: ein Ausgabepfad ist ueber die Schnittstelle nicht setzbar")
    void ausgabepfadIstNichtSetzbar() {
        for (Method methode : ReportStore.class.getMethods()) {
            for (Class<?> typ : methode.getParameterTypes()) {
                assertFalse(typ == Path.class || typ == File.class || typ == URI.class,
                        "Die Berichtsschnittstelle darf keinen Pfad entgegennehmen: " + methode);
            }
        }
        Method save = java.util.Arrays.stream(ReportStore.class.getMethods())
                .filter(methode -> "save".equals(methode.getName())).findFirst().orElseThrow();
        assertEquals(List.of(StoredSnapshot.class, DiagnosisDocument.class), List.of(save.getParameterTypes()));

        assertThrows(IllegalArgumentException.class, () -> SnapshotId.of("../../etc/x"));
    }

    @Test
    @DisplayName("S10: gespeicherte Pfade beginnen mit reports/ und bleiben im Ausgabebereich")
    void gespeichertePfadeBleibenImAusgabebereich() {
        StoredReport report = harness.reports().save(snapshot, gueltigerEntwurf());

        assertTrue(report.jsonPath().startsWith(FileReportStore.REPORTS_DIRECTORY + "/"), report.jsonPath());
        assertTrue(report.markdownPath().startsWith(FileReportStore.REPORTS_DIRECTORY + "/"), report.markdownPath());
        assertFalse(report.jsonPath().contains(".."), report.jsonPath());
        Path json = tempDir.resolve(report.jsonPath()).normalize();
        assertTrue(json.startsWith(tempDir.toAbsolutePath().normalize()), json.toString());
        assertTrue(Files.isRegularFile(json));
        assertFalse(Files.exists(tempDir.resolve("etc")));
    }

    @Test
    @DisplayName("S10: ein Pfad wie ../../etc/x wird nirgends akzeptiert und schreibt nichts")
    void traversalPfadWirdNirgendsAkzeptiert() throws java.io.IOException {
        Path ausgabeEltern = tempDir.toAbsolutePath().normalize().getParent();
        java.util.Set<String> vorher = dateinamen(ausgabeEltern);
        StoredSnapshot manipuliert = AcceptanceSupport.withManifestSnapshotId(snapshot, "../../etc/x");
        DiagnosisDocument entwurf = AcceptanceSupport.validDocument("../../etc/x", "EV-0001", 5);

        ReportException error = assertThrows(ReportException.class,
                () -> harness.reports().save(manipuliert, entwurf));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertFalse(Files.exists(tempDir.resolve("etc")));
        assertEquals(vorher, dateinamen(ausgabeEltern),
                "Ausserhalb des Ausgabebereichs darf keine Datei oder kein Verzeichnis entstehen.");
        assertNichtsGeschrieben();
    }

    private static java.util.Set<String> dateinamen(Path verzeichnis) throws java.io.IOException {
        if (!Files.isDirectory(verzeichnis)) {
            return java.util.Set.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(verzeichnis)) {
            java.util.Set<String> namen = new java.util.TreeSet<>();
            stream.forEach(pfad -> namen.add(pfad.getFileName().toString()));
            return java.util.Set.copyOf(namen);
        }
    }

    @Test
    @DisplayName("S10: ein vorhandener Bericht wird nicht ueberschrieben")
    void vorhandenerBerichtWirdNichtUeberschrieben() throws java.io.IOException {
        StoredReport erster = harness.reports().save(snapshot, gueltigerEntwurf());
        String ersterInhalt = AcceptanceSupport.readString(tempDir.resolve(erster.jsonPath()));

        StoredReport zweiter = harness.reports().save(snapshot, gueltigerEntwurf());

        assertNotEquals(erster.reportId(), zweiter.reportId());
        assertEquals(ersterInhalt, AcceptanceSupport.readString(tempDir.resolve(erster.jsonPath())),
                "Der erste Bericht darf nicht veraendert werden.");
        assertEquals(ch.so.agi.ops.util.Hashes.sha256Hex(java.nio.file.Files.readAllBytes(
                tempDir.resolve(erster.jsonPath()))), erster.jsonSha256());
        assertEquals(2, harness.reports().list(SnapshotId.of(snapshot.manifest().snapshotId())).size());
    }
}
