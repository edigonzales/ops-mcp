package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.snapshot.SnapshotId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests der dateibasierten Berichtsablage inklusive Redaktion, Rechten und Snapshotbindung.
 *
 * <p>Alle Tests arbeiten ausschliesslich in einem temporaeren Verzeichnis, ohne Netz und ohne
 * Zugriff auf einen Snapshotbestand.</p>
 */
class FileReportStoreTest {

    private static final Instant WRITTEN_AT = Instant.parse("2026-10-01T11:00:00Z");

    @TempDir
    Path tempDir;

    private FileReportStore store() {
        return new FileReportStore(tempDir, Clock.fixed(WRITTEN_AT, ZoneOffset.UTC), new Random(42));
    }

    private static DiagnosisDocument validDocument() {
        return ReportFixtures.document().build();
    }

    @Test
    void speichertUndLiest() {
        FileReportStore store = store();

        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());

        assertTrue(report.reportId().startsWith(FileReportStore.REPORT_ID_PREFIX), report.reportId());
        assertEquals("reports/" + ReportFixtures.SNAPSHOT_ID + "/" + report.reportId() + "/diagnosis.json",
                report.jsonPath());
        assertEquals("reports/" + ReportFixtures.SNAPSHOT_ID + "/" + report.reportId() + "/diagnosis.md",
                report.markdownPath());
        assertTrue(Files.isRegularFile(tempDir.resolve(report.jsonPath())));
        assertTrue(Files.isRegularFile(tempDir.resolve(report.markdownPath())));
        assertEquals(64, report.jsonSha256().length());
        assertEquals(64, report.markdownSha256().length());
        assertEquals(WRITTEN_AT, report.createdAt());
        assertTrue(report.validation().valid(), () -> report.validation().errors().toString());

        DiagnosisDocument read = store.read(SnapshotId.of(ReportFixtures.SNAPSHOT_ID), report.reportId());

        assertEquals(validDocument(), read);
    }

    @Test
    void pfadLiegtUnterReports() {
        FileReportStore store = store();

        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());

        assertTrue(report.jsonPath().startsWith("reports/"), report.jsonPath());
        assertTrue(report.markdownPath().startsWith("reports/"), report.markdownPath());
        assertTrue(Files.isDirectory(tempDir.resolve("reports").resolve(ReportFixtures.SNAPSHOT_ID)
                .resolve(report.reportId())));
        assertFalse(report.jsonPath().contains(".."), report.jsonPath());
    }

    @Test
    void vorhandenerBerichtWirdNichtUeberschrieben() {
        FileReportStore store = store();

        StoredReport first = store.save(ReportFixtures.snapshot(), validDocument());
        StoredReport second = store.save(ReportFixtures.snapshot(), validDocument());

        assertNotEquals(first.reportId(), second.reportId());
        assertTrue(Files.isRegularFile(tempDir.resolve(first.jsonPath())));
        assertTrue(Files.isRegularFile(tempDir.resolve(second.jsonPath())));
        assertEquals(2, store.list(SnapshotId.of(ReportFixtures.SNAPSHOT_ID)).size());
    }

    @Test
    void ungueltigerBerichtSchreibtNichts() {
        FileReportStore store = store();
        DiagnosisDocument document = ReportFixtures.document()
                .recommendations(ReportFixtures.recommendation("Startbudget prüfen.", true))
                .build();

        ReportException error = assertThrows(ReportException.class,
                () -> store.save(ReportFixtures.snapshot(), document));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertFalse(error.errors().isEmpty());
        assertFalse(Files.exists(tempDir.resolve("reports")), "Bei ungueltigem Bericht darf nichts geschrieben werden.");
    }

    @Test
    void fremderSnapshotKannNichtReferenziertWerden() {
        FileReportStore store = store();
        DiagnosisDocument document = ReportFixtures.document()
                .snapshotId(ReportFixtures.OTHER_SNAPSHOT_ID)
                .build();

        ReportException error = assertThrows(ReportException.class,
                () -> store.save(ReportFixtures.snapshot(), document));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.errors().stream().anyMatch(entry -> entry.message().contains("Snapshotbindung")),
                error.errors().toString());
        assertFalse(Files.exists(tempDir.resolve("reports")), "Bei fremdem Snapshot darf nichts geschrieben werden.");
    }

    @Test
    void markdownEnthaeltDenSchlusssatz() throws IOException {
        FileReportStore store = store();

        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());
        String markdown = Files.readString(tempDir.resolve(report.markdownPath()), StandardCharsets.UTF_8);

        assertTrue(markdown.contains("Keine Änderungen an den untersuchten Systemen ausgeführt."), markdown);
    }

    @Test
    void dateirechteSindRestriktiv() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-Dateirechte werden auf diesem Dateisystem nicht unterstuetzt.");

        FileReportStore store = store();
        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());
        Path json = tempDir.resolve(report.jsonPath());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(json.getParent()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(json));
        assertEquals(Files.getPosixFilePermissions(json),
                Files.getPosixFilePermissions(tempDir.resolve(report.markdownPath())));
    }

    @Test
    void listetGespeicherteBerichte() {
        FileReportStore store = store();
        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());

        List<StoredReport> reports = store.list(SnapshotId.of(ReportFixtures.SNAPSHOT_ID));

        assertEquals(1, reports.size());
        assertEquals(report.reportId(), reports.getFirst().reportId());
        assertEquals(report.jsonPath(), reports.getFirst().jsonPath());
        assertEquals(report.jsonSha256(), reports.getFirst().jsonSha256());
        assertTrue(reports.getFirst().validation().valid());
        assertTrue(store.list(SnapshotId.of(ReportFixtures.OTHER_SNAPSHOT_ID)).isEmpty());
    }

    @Test
    void berichtWirdVorDemSpeichernRedigiert() throws IOException {
        FileReportStore store = store();
        DiagnosisDocument document = ReportFixtures.document()
                .summary("Zugang: password=SuperSecretValue123")
                .build();

        StoredReport report = store.save(ReportFixtures.snapshot(), document);

        String json = Files.readString(tempDir.resolve(report.jsonPath()), StandardCharsets.UTF_8);
        String markdown = Files.readString(tempDir.resolve(report.markdownPath()), StandardCharsets.UTF_8);
        assertFalse(json.contains("SuperSecretValue123"), json);
        assertFalse(markdown.contains("SuperSecretValue123"), markdown);
        assertTrue(report.validation().warnings().stream().anyMatch(warning -> warning.contains("redigiert")),
                report.validation().warnings().toString());
    }

    @Test
    void unbekannterBerichtUndUnzulaessigeIdWerdenAbgewiesen() {
        FileReportStore store = store();

        ReportException missing = assertThrows(ReportException.class,
                () -> store.read(SnapshotId.of(ReportFixtures.SNAPSHOT_ID), "RP-00000000"));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());

        ReportException traversal = assertThrows(ReportException.class,
                () -> store.read(SnapshotId.of(ReportFixtures.SNAPSHOT_ID), "../fremder-bericht"));
        assertEquals(ErrorCode.SCOPE_DENIED, traversal.code());
    }

    @Test
    void gespeicherterBerichtWirdGegenDenSnapshotErneutValidiert() {
        FileReportStore store = store();
        StoredReport report = store.save(ReportFixtures.snapshot(), validDocument());

        DiagnosisDocument read = store.read(SnapshotId.of(ReportFixtures.SNAPSHOT_ID), report.reportId(),
                ReportFixtures.snapshot());

        assertEquals(ReportFixtures.SNAPSHOT_ID, read.snapshotId());
    }
}
