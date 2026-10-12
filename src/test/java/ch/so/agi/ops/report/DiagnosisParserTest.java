package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests des strikten Parsers fuer {@code diagnosis-v1}. */
class DiagnosisParserTest {

    @Test
    void gueltigerBerichtWirdGelesen() {
        DiagnosisDocument document = DiagnosisParser.parse(validJson(ReportFixtures.SNAPSHOT_ID),
                DiagnosisDocument.MAX_DRAFT_BYTES);

        assertEquals(DiagnosisDocument.SCHEMA_VERSION, document.schemaVersion());
        assertEquals(ReportFixtures.SNAPSHOT_ID, document.snapshotId());
        assertEquals(DiagnosisDocument.LANGUAGE_DE_CH, document.language());
        assertEquals(AssessmentStatus.PROBABLE_CAUSE, document.assessment());
        assertEquals(1, document.facts().size());
        assertEquals("F-1", document.facts().getFirst().id());
        assertEquals(3, document.facts().getFirst().evidence().getFirst().startLine());
        assertEquals(Strength.SUPPORTED, document.hypotheses().getFirst().strength());
        assertEquals(1, document.hypotheses().getFirst().supports().size());
        assertNull(document.timeline().getFirst().at());
        assertEquals("unknown", document.timeline().getFirst().timeBasis());
        assertFalse(document.recommendations().getFirst().executed());
        assertTrue(document.actionsTaken().isEmpty());
    }

    @Test
    void unbekanntesFeldWirdAbgewiesen() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID)
                .replace("\"actionsTaken\": []", "\"actionsTaken\": [], \"command\": \"oc delete pod\"");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("command"), error.getMessage());
    }

    @Test
    void unbekanntesFeldInVerschachteltemObjektWirdAbgewiesen() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID)
                .replace("\"id\": \"F-1\"", "\"id\": \"F-1\", \"command\": \"oc delete pod\"");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("command"), error.getMessage());
    }

    @Test
    void ungueltigesJsonWirdAbgewiesen() {
        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse("{ \"schemaVersion\": 1, ", DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("gueltiges JSON"), error.getMessage());
    }

    @Test
    void zuGrossesDokumentWirdAbgewiesen() {
        byte[] json = validJson(ReportFixtures.SNAPSHOT_ID).getBytes(StandardCharsets.UTF_8);

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, json.length - 1));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("zu gross"), error.getMessage());
    }

    @Test
    void obergrenzeDesEntwurfsGiltAuchBeiGrossemMaxBytes() {
        String gross = validJson(ReportFixtures.SNAPSHOT_ID);
        String aufgeblaeht = gross.replace("\"language\": \"de-CH\"",
                "\"language\": \"" + "a".repeat(DiagnosisDocument.MAX_DRAFT_BYTES) + "\"");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(aufgeblaeht, Integer.MAX_VALUE));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
    }

    @Test
    void unbekannteBewertungWirdAbgewiesen() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID).replace("probable_cause", "certain_cause");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("Unbekannte Bewertung"), error.getMessage());
    }

    @Test
    void unbekannteBelegstaerkeWirdAbgewiesen() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID).replace("\"strength\": \"supported\"",
                "\"strength\": \"sehr_sicher\"");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("Unbekannte Belegstaerke"), error.getMessage());
    }

    @Test
    void fehlendesPflichtfeldWirdAbgewiesen() {
        String json = """
                {
                  "schemaVersion": 1,
                  "snapshotId": "%s",
                  "language": "de-CH",
                  "assessment": "probable_cause",
                  "summary": "Zusammenfassung ohne Titel."
                }
                """.formatted(ReportFixtures.SNAPSHOT_ID);

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
        assertTrue(error.getMessage().contains("title"), error.getMessage());
    }

    @Test
    void unbekannteBewertungWirdAuchAlsZahlAbgewiesen() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID).replace("\"assessment\": \"probable_cause\"",
                "\"assessment\": 3");

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
    }

    /**
     * Wichtig fuer S10: {@code executed: true} muss geparst werden und darf nicht schon im
     * Parser verschwinden. Abgewiesen wird es erst durch {@link DiagnosisValidator}.
     */
    @Test
    void executedTrueWirdGeparstUndNichtVerschluckt() {
        String json = validJson(ReportFixtures.SNAPSHOT_ID).replace("\"executed\": false", "\"executed\": true");

        DiagnosisDocument document = DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES);

        assertTrue(document.recommendations().getFirst().executed());
    }

    @Test
    void fehlendesExecutedWirdAbgewiesen() {
        String json = """
                {
                  "schemaVersion": 1,
                  "snapshotId": "%s",
                  "language": "de-CH",
                  "assessment": "probable_cause",
                  "title": "Titel",
                  "summary": "Zusammenfassung",
                  "recommendations": [
                    {
                      "description": "Vorschlag ohne ausdrueckliches executed.",
                      "basedOnHypotheses": []
                    }
                  ]
                }
                """.formatted(ReportFixtures.SNAPSHOT_ID);

        ReportException error = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));

        assertEquals(ErrorCode.REPORT_INVALID, error.code());
    }

    private static String validJson(String snapshotId) {
        return """
                {
                  "schemaVersion": 1,
                  "snapshotId": "%s",
                  "language": "de-CH",
                  "assessment": "probable_cause",
                  "title": "Liveness-Prüfung unterbricht die Startphase",
                  "summary": "Der Container wird während der Initialisierung neu gestartet.",
                  "facts": [
                    {
                      "id": "F-1",
                      "statement": "Das erfasste Event nennt einen Neustart nach fehlgeschlagener Liveness-Prüfung.",
                      "evidence": [
                        {"evidenceId": "EV-0004", "startLine": 3, "endLine": 6}
                      ]
                    }
                  ],
                  "hypotheses": [
                    {
                      "id": "H-1",
                      "category": "startup-budget",
                      "statement": "Die konfigurierte Startabsicherung ist zu knapp.",
                      "strength": "supported",
                      "supports": ["F-1"],
                      "counterEvidence": [],
                      "limitations": ["Die nötige Startdauer ist nicht vollständig gemessen."]
                    }
                  ],
                  "timeline": [
                    {
                      "at": null,
                      "timeBasis": "unknown",
                      "description": "Liveness-Fehler und Neustart sind dokumentiert.",
                      "evidence": [
                        {"evidenceId": "EV-0004", "startLine": 1, "endLine": 8}
                      ]
                    }
                  ],
                  "recommendations": [
                    {
                      "description": "Eine Startup-Probe mit nachgewiesenem Health-Endpunkt prüfen.",
                      "rationale": "Die Startphase soll abgesichert werden.",
                      "basedOnHypotheses": ["H-1"],
                      "risk": "Ein zu grosses Budget könnte echte Startfehler später sichtbar machen.",
                      "verification": "Startdauer und Neustarts nach menschlich freigegebener Änderung prüfen.",
                      "executed": false
                    }
                  ],
                  "openQuestions": ["Welches Startbudget deckt die beobachteten erfolgreichen Starts ab?"],
                  "limitations": ["Synthetischer Beispieldatensatz; keine Live-Abnahme."],
                  "actionsTaken": []
                }
                """.formatted(snapshotId);
    }

    @Test
    void namespacedExtensionFieldsAreRejected() {
        // Der Bericht ist ein geschlossener Vertrag: auch x--Felder werden abgewiesen.
        String json = validJson(ReportFixtures.SNAPSHOT_ID)
                .replace("\"actionsTaken\": []", "\"actionsTaken\": [], \"x-team\": \"gis\"");
        ReportException ex = assertThrows(ReportException.class,
                () -> DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES));
        assertEquals(ErrorCode.REPORT_INVALID, ex.code());
    }
}
