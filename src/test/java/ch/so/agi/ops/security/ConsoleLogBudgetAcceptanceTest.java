package ch.so.agi.ops.security;

import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import ch.so.agi.ops.evidence.FileSnapshotStore;
import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.SnapshotId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budgets – grosse Jenkins-Logs innerhalb und ausserhalb des Bytebudgets.
 *
 * <p>Belegt: Ein grosses, aber innerhalb des Budgets liegendes Console-Log enthaelt den
 * wichtigen Fehler nahe dem Ende – auch im auf 200 Zeilen begrenzten Evidence-Ausschnitt.
 * Ein groesserer, vor dem Ende abgebrochener Stream zeigt sichtbare Trunkierung mit
 * Begruendung. Der Adapter laeuft gegen einen lokalen Testserver auf {@code 127.0.0.1}.</p>
 */
class ConsoleLogBudgetAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final String FEHLERZEILE =
            "2026-10-01T10:05:00Z ERROR: Rollout deployment/fixture-app hat das Warten abgebrochen";

    private static RawEvidence consoleLogEvidence(ConsoleLogEvidence evidence) {
        return new RawEvidence("jenkins-console-log", EvidenceType.JENKINS_CONSOLE_LOG,
                AcceptanceSupport.JENKINS_ID, null, null,
                Locator.of("/job/fixture-folder/job/fixture-app/42/consoleText"), evidence.lines(), null,
                "Jenkins-Console-Log (bereinigt)", List.of());
    }

    @Test
    @DisplayName("Budgets: grosses Log innerhalb des Budgets enthaelt den Fehler nahe dem Ende")
    void grossesLogInnerhalbDesBudgetsEnthaeltDenFehler() {
        byte[] log = AcceptanceSupport.consoleLogMitFehlerAmEnde(8000, FEHLERZEILE);
        try (FixtureHttpServer server = FixtureHttpServer.serving(log)) {
            OpsConfig config = AcceptanceSupport.jenkinsConfig(tempDir.resolve("ausgabe-gross"),
                    server.baseUrl(), 8L * 1024 * 1024);

            long start = System.nanoTime();
            SourceResult<ConsoleLogEvidence> result = AcceptanceSupport.consoleLog(config);
            long dauerMillis = (System.nanoTime() - start) / 1_000_000;

            assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
            ConsoleLogEvidence evidence = result.data();
            assertFalse(evidence.truncated());
            assertEquals("", evidence.truncatedReason());
            assertTrue(evidence.lines().size() >= 7990, "Das Log muss vollstaendig gelesen werden: "
                    + evidence.lines().size());
            int index = evidence.lines().indexOf(FEHLERZEILE);
            assertTrue(index >= evidence.lines().size() - 3,
                    "Der Fehler muss nahe dem Ende stehen; Index " + index + " von " + evidence.lines().size());
            assertTrue(evidence.errorAnchors().stream().anyMatch(anchor -> anchor.line().contains("ERROR: Rollout")),
                    evidence.errorAnchors().toString());
            assertTrue(dauerMillis < 30_000, "Kein Haenger: " + dauerMillis + " ms");

            FileSnapshotStore store = new FileSnapshotStore(config.storageRoot(),
                    Clock.fixed(AcceptanceSupport.FIXED_NOW, ZoneOffset.UTC), new Random(7),
                    YamlSupport.jsonMapper());
            SnapshotId id = store.save(AcceptanceSupport.draft(List.of(consoleLogEvidence(evidence)), Map.of()))
                    .snapshotId();

            int gesamt = evidence.lines().size();
            EvidencePage seite = store.readEvidence(id, "EV-0001", Math.max(1, gesamt - 199), 10000);

            assertEquals(200, seite.lines().size(), "Es werden hoechstens 200 Zeilen geliefert.");
            assertFalse(seite.hasMore());
            assertEquals(gesamt, seite.endLine());
            assertTrue(seite.lines().stream().anyMatch(zeile -> zeile.contains("ERROR: Rollout")),
                    "Der Fehler nahe dem Ende muss im gelieferten Ausschnitt enthalten sein:\n" + seite.render());
        }
    }

    @Test
    @DisplayName("Budgets: groesserer, vor dem Ende abgebrochener Stream zeigt sichtbare Trunkierung")
    void groesseresLogVorDemEndeZeigtTrunkierung() {
        byte[] log = AcceptanceSupport.consoleLogMitFehlerAmEnde(5000, FEHLERZEILE);
        try (FixtureHttpServer server = FixtureHttpServer.serving(log)) {
            OpsConfig config = AcceptanceSupport.jenkinsConfig(tempDir.resolve("ausgabe-groesser"),
                    server.baseUrl(), 16384);

            SourceResult<ConsoleLogEvidence> result = AcceptanceSupport.consoleLog(config);

            assertEquals(SourceStatus.TRUNCATED, result.status(), result.errors().toString());
            ConsoleLogEvidence evidence = result.data();
            assertTrue(evidence.truncated(), "Die Trunkierung muss sichtbar sein.");
            assertFalse(evidence.truncatedReason().isBlank(), "Die Truncation-Notiz darf nicht leer sein.");
            assertTrue(evidence.truncatedReason().contains("Bytebudget")
                    || evidence.truncatedReason().contains("NICHT"), evidence.truncatedReason());
            assertTrue(result.notes().stream().anyMatch(note -> note.contains("nicht vollstaendig gelesen")),
                    result.notes().toString());
            assertEquals((long) log.length, evidence.totalBytesAvailable().longValue());
            assertTrue(evidence.tailRequestedFromByte() != null,
                    "Bei bekannter Groesse muss ein Endausschnitt gelesen werden.");
            assertTrue(evidence.bytesRead() < log.length, "Es darf nicht das gesamte Log gelesen worden sein.");

            String text = String.join("\n", evidence.lines());
            assertTrue(text.contains("Endausschnitt"), text);
            assertTrue(text.contains("ERROR: Rollout"), "Der Fehler im Endausschnitt fehlt.");
        }
    }

    @Test
    @DisplayName("Budgets: ein Server ohne Range-Unterstuetzung wird als Trunkierung ausgewiesen")
    void serverOhneRangeUnterstuetzungZeigtTrunkierung() {
        byte[] log = AcceptanceSupport.consoleLogMitFehlerAmEnde(5000, FEHLERZEILE);
        try (FixtureHttpServer server = FixtureHttpServer.ignoringRanges(log)) {
            OpsConfig config = AcceptanceSupport.jenkinsConfig(tempDir.resolve("ausgabe-ohne-range"),
                    server.baseUrl(), 16384);

            SourceResult<ConsoleLogEvidence> result = AcceptanceSupport.consoleLog(config);

            ConsoleLogEvidence evidence = result.data();
            assertTrue(evidence.truncated(), "Ohne Endausschnitt muss die Trunkierung sichtbar bleiben.");
            assertFalse(evidence.truncatedReason().isBlank());
            assertTrue(evidence.truncatedReason().contains("Range")
                    || evidence.truncatedReason().contains("NICHT"), evidence.truncatedReason());
        }
    }
}
