package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.report.DiagnosisDocument.Recommendation;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests der formalen Validierung nach {@code SCHNITTSTELLEN.md} Abschnitt 6. */
class DiagnosisValidatorTest {

    private final DiagnosisValidator validator = new DiagnosisValidator();
    private final StoredSnapshot snapshot = ReportFixtures.snapshot();

    @Test
    void gueltigerBerichtWirdAkzeptiert() {
        ReportValidationResult result = validator.validate(ReportFixtures.document().build(), snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void executedTrueWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .recommendations(ReportFixtures.recommendation("Startbudget prüfen.", true))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "recommendations[0].executed"), result.errors().toString());
        assertTrue(result.errors().stream().allMatch(error -> error.code() == ErrorCode.REPORT_INVALID));
    }

    @Test
    void unbekannteEvidenceIdWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .facts(ReportFixtures.fact("F-1", "Behauptung.", ReportFixtures.ref("EV-9999")))
                .hypotheses(ReportFixtures.hypothesis("H-1", Strength.SUPPORTED, List.of("F-1")))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains("EV-9999")),
                result.errors().toString());
    }

    @Test
    void leereEvidenceListeImFactWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .facts(ReportFixtures.fact("F-1", "Behauptung ohne Beleg."))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "facts[0].evidence"), result.errors().toString());
    }

    @Test
    void actionsTakenNichtLeerWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .actionsTaken(List.of("oc rollout restart deployment/fixture-modelfinder"))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "actionsTaken"), result.errors().toString());
    }

    @Test
    void causeIdentifiedMitAmbiguousLinkWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.CAUSE_IDENTIFIED)
                .build();

        ReportValidationResult result = validator.validate(document,
                ReportFixtures.snapshot(ReportFixtures.SNAPSHOT_ID, LinkStrength.AMBIGUOUS));

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "assessment"), result.errors().toString());
    }

    @Test
    void causeIdentifiedMitUnlinkedLinkWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.CAUSE_IDENTIFIED)
                .build();

        ReportValidationResult result = validator.validate(document,
                ReportFixtures.snapshot(ReportFixtures.SNAPSHOT_ID, LinkStrength.UNLINKED));

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "assessment"), result.errors().toString());
    }

    @Test
    void causeIdentifiedMitSupportedHypotheseUndExactLinkIstGueltig() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.CAUSE_IDENTIFIED)
                .build();

        ReportValidationResult result = validator.validate(document,
                ReportFixtures.snapshot(ReportFixtures.SNAPSHOT_ID, LinkStrength.EXACT));

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void causeIdentifiedOhneSupportedHypotheseWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.CAUSE_IDENTIFIED)
                .hypotheses(ReportFixtures.hypothesis("H-1", Strength.PLAUSIBLE, List.of("F-1")))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "assessment"), result.errors().toString());
    }

    @Test
    void scriptImTextWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .title("<script>alert(1)</script>")
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "title"), result.errors().toString());
    }

    @Test
    void iframeUndJavascriptImTextWerdenAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .summary("Bitte <iframe src=\"http://example.invalid\"></iframe> öffnen.")
                .limitations(List.of("Die URL javascript:alert(1) ist kein Beleg."))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "summary"), result.errors().toString());
        assertTrue(hasErrorFor(result, "limitations[0]"), result.errors().toString());
    }

    @Test
    void insufficientEvidenceIstGueltig() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.INSUFFICIENT_EVIDENCE)
                .facts(List.of())
                .hypotheses(List.of())
                .timeline(List.of())
                .recommendations(List.of())
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void noFailureEvidencedIstGueltig() {
        DiagnosisDocument document = ReportFixtures.document()
                .assessment(AssessmentStatus.NO_FAILURE_EVIDENCED)
                .facts(List.of())
                .hypotheses(List.of())
                .recommendations(List.of())
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void zeilenbereichAusserhalbDerEvidenceWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .facts(ReportFixtures.fact("F-1", "Behauptung.",
                        ReportFixtures.ref(ReportFixtures.EVIDENCE_EVENT, 1, 50)))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "facts[0].evidence[0].endLine"), result.errors().toString());
    }

    @Test
    void startZeileNullWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .facts(ReportFixtures.fact("F-1", "Behauptung.",
                        ReportFixtures.ref(ReportFixtures.EVIDENCE_EVENT, 0, 3)))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "facts[0].evidence[0].startLine"), result.errors().toString());
    }

    @Test
    void unbekannteFactReferenzInSupportsWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .hypotheses(ReportFixtures.hypothesis("H-1", Strength.SUPPORTED, List.of("F-99")))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "hypotheses[0].supports"), result.errors().toString());
    }

    @Test
    void unbekannteHypotheseInRecommendationWirdAbgewiesen() {
        Recommendation recommendation = new Recommendation("Vorschlag.", "Begruendung.", List.of("H-9"),
                "Risiko.", "Verifikation.", false);
        DiagnosisDocument document = ReportFixtures.document()
                .recommendations(recommendation)
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "recommendations[0].basedOnHypotheses"), result.errors().toString());
    }

    @Test
    void snapshotbindungWirdGeprueft() {
        DiagnosisDocument document = ReportFixtures.document()
                .snapshotId(ReportFixtures.OTHER_SNAPSHOT_ID)
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains("Snapshotbindung")),
                result.errors().toString());
    }

    @Test
    void falscheSchemaVersionUndLeereSpracheWerdenAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .schemaVersion(2)
                .language(" ")
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "schemaVersion"), result.errors().toString());
        assertTrue(hasErrorFor(result, "language"), result.errors().toString());
    }

    @Test
    void zuLangeTexteWerdenAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .title("t".repeat(DiagnosisValidator.MAX_TITLE_LENGTH + 1))
                .summary("s".repeat(DiagnosisValidator.MAX_SUMMARY_LENGTH + 1))
                .facts(ReportFixtures.fact("F-1", "f".repeat(DiagnosisValidator.MAX_STATEMENT_LENGTH + 1),
                        ReportFixtures.ref(ReportFixtures.EVIDENCE_EVENT)))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "title"), result.errors().toString());
        assertTrue(hasErrorFor(result, "summary"), result.errors().toString());
        assertTrue(hasErrorFor(result, "facts[0].statement"), result.errors().toString());
    }

    @Test
    void zeitpunktOhneZeitzoneWirdAbgewiesen() {
        DiagnosisDocument document = ReportFixtures.document()
                .timeline(ReportFixtures.timelineEntry("2026-10-01 10:02:00"))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertFalse(result.valid());
        assertTrue(hasErrorFor(result, "timeline[0].at"), result.errors().toString());
    }

    @Test
    void zeitpunktNullErzeugtNurEineWarnung() {
        DiagnosisDocument document = ReportFixtures.document()
                .timeline(ReportFixtures.timelineEntry(null))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("Zeitbasis")),
                result.warnings().toString());
    }

    @Test
    void gueltigerZeitpunktWirdAkzeptiert() {
        DiagnosisDocument document = ReportFixtures.document()
                .timeline(ReportFixtures.timelineEntry("2026-10-01T10:02:00Z"))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void supportedOhneSupportsErzeugtNurEineWarnung() {
        DiagnosisDocument document = ReportFixtures.document()
                .hypotheses(ReportFixtures.hypothesis("H-1", Strength.SUPPORTED, List.of()))
                .build();

        ReportValidationResult result = validator.validate(document, snapshot);

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("supported")),
                result.warnings().toString());
    }

    @Test
    void unvollstaendigeErfassungErzeugtWarnungUndKeinenFehler() {
        DiagnosisDocument document = ReportFixtures.document().build();

        ReportValidationResult result = validator.validate(document,
                ReportFixtures.snapshot(ReportFixtures.SNAPSHOT_ID, LinkStrength.EXACT, CollectionStatus.PARTIAL, 12));

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    private static boolean hasErrorFor(ReportValidationResult result, String field) {
        return result.errors().stream().anyMatch(error -> field.equals(error.sourceId()));
    }
}
