package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests der deterministischen Markdown-Darstellung. */
class DiagnosisMarkdownRendererTest {

    private final DiagnosisMarkdownRenderer renderer = new DiagnosisMarkdownRenderer();

    @Test
    void zeitpunktNullErzeugtKeinenErfundenenZeitstempel() {
        DiagnosisDocument document = ReportFixtures.document()
                .timeline(ReportFixtures.timelineEntry(null))
                .build();

        String markdown = renderer.render(document, ReportFixtures.snapshotWithoutTimesAndLinks());

        assertTrue(markdown.contains("Zeitpunkt nicht belegt"), markdown);
        assertFalse(markdown.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T.*"), markdown);
    }

    @Test
    void htmlWirdEscaped() {
        DiagnosisDocument document = ReportFixtures.document()
                .summary("<script>alert(1)</script>")
                .build();

        String markdown = renderer.render(document, ReportFixtures.snapshot());

        assertFalse(markdown.contains("<script>"), markdown);
        assertTrue(markdown.contains("&lt;script&gt;"), markdown);
    }

    @Test
    void textErzeugtKeineBilder() {
        DiagnosisDocument document = ReportFixtures.document()
                .summary("![Bild](http://example.invalid/bild.png) und <img src=\"http://example.invalid/b.png\">")
                .build();

        String markdown = renderer.render(document, ReportFixtures.snapshot());

        assertFalse(markdown.contains("!["), markdown);
        assertFalse(markdown.contains("<img"), markdown);
        assertTrue(markdown.contains("!\\["), markdown);
    }

    @Test
    void belegreferenzenSindReinerText() {
        DiagnosisDocument document = ReportFixtures.document().build();

        String markdown = renderer.render(document, ReportFixtures.snapshot());

        assertTrue(markdown.contains("EV-0004 Zeile 3-6"), markdown);
        assertFalse(markdown.contains("](http"), markdown);
    }

    @Test
    void herkunftWirdGenannt() {
        String markdown = renderer.render(ReportFixtures.document().build(), ReportFixtures.snapshot());

        assertTrue(markdown.contains(ReportFixtures.SNAPSHOT_ID), markdown);
        assertTrue(markdown.contains("fixture"), markdown);
        assertTrue(markdown.contains("complete"), markdown);
        assertTrue(markdown.contains("ocp"), markdown);
        assertTrue(markdown.contains("COLLECTED"), markdown);
    }

    @Test
    void bewertungUndBelegstaerkeMitDeutschemLabel() {
        String markdown = renderer.render(ReportFixtures.document().build(), ReportFixtures.snapshot());

        assertTrue(markdown.contains("Wahrscheinliche Ursache"), markdown);
        assertTrue(markdown.contains("probable_cause"), markdown);
        assertTrue(markdown.contains("belegt (`supported`)"), markdown);
    }

    @Test
    void vorschlaegeSindAlsNichtAusgefuehrtMarkiert() {
        String markdown = renderer.render(ReportFixtures.document().build(), ReportFixtures.snapshot());

        assertTrue(markdown.contains("nicht ausgeführt"), markdown);
        assertTrue(markdown.contains("Risiko:"), markdown);
        assertTrue(markdown.contains("Begründung:"), markdown);
    }

    @Test
    void faktenUndHypothesenSindGetrenntAusgewiesen() {
        String markdown = renderer.render(ReportFixtures.document().build(), ReportFixtures.snapshot());

        assertTrue(markdown.contains("## Fakten"), markdown);
        assertTrue(markdown.contains("## Hypothesen"), markdown);
        assertTrue(markdown.contains("keine belegten Fakten"), markdown);
        assertTrue(markdown.contains("## Offene Fragen"), markdown);
        assertTrue(markdown.contains("## Grenzen der Untersuchung"), markdown);
    }

    @Test
    void endetMitDemSchlusssatz() {
        String markdown = renderer.render(ReportFixtures.document().build(), ReportFixtures.snapshot());

        assertTrue(markdown.endsWith(DiagnosisMarkdownRenderer.NO_CHANGES_SENTENCE), markdown);
        assertTrue(markdown.contains("Keine Änderungen an den untersuchten Systemen ausgeführt."), markdown);
    }

    @Test
    void renderIstDeterministisch() {
        DiagnosisDocument document = ReportFixtures.document().build();
        StoredSnapshot snapshot = ReportFixtures.snapshot();

        assertEquals(renderer.render(document, snapshot), renderer.render(document, snapshot));
    }

    @Test
    void fehlendeAngabenWerdenNichtErfunden() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.INSUFFICIENT_EVIDENCE)
                .facts(List.of())
                .hypotheses(List.of())
                .timeline(List.of())
                .recommendations(List.of())
                .build();

        String markdown = renderer.render(document, ReportFixtures.snapshotWithoutTimesAndLinks());

        assertTrue(markdown.contains("Belege unzureichend"), markdown);
        assertTrue(markdown.contains("Keine Fakten dokumentiert."), markdown);
        assertTrue(markdown.contains("nicht belegt"), markdown);
    }
}
