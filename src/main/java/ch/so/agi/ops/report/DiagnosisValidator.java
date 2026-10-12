package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.report.DiagnosisDocument.EvidenceRef;
import ch.so.agi.ops.report.DiagnosisDocument.Fact;
import ch.so.agi.ops.report.DiagnosisDocument.Hypothesis;
import ch.so.agi.ops.report.DiagnosisDocument.Recommendation;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import ch.so.agi.ops.report.DiagnosisDocument.TimelineEntry;
import ch.so.agi.ops.snapshot.EvidenceIndexEntry;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.SnapshotLink;
import ch.so.agi.ops.snapshot.SnapshotManifest;
import ch.so.agi.ops.snapshot.StoredSnapshot;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formale Validierung eines Diagnoseberichts gegen den gebundenen Snapshot.
 *
 * <p>Geprueft werden Struktur, Snapshotbindung, Referenzexistenz, Zeilenbereiche,
 * Textobergrenzen und die Statusregeln des Diagnosisvertrags
 * ({@code SCHNITTSTELLEN.md} Abschnitt 6). Die Validierung bestaetigt <em>nicht</em>, dass ein
 * Beleg den Text inhaltlich traegt; das bleibt Gegenstand der Agentenabnahme und der
 * menschlichen Pruefung.</p>
 *
 * <p>Zusaetzlich werden ausfuehrbare HTML-/Scriptanteile abgewiesen (S10). Alle Fehler sind
 * {@link OpsError} mit {@link ch.so.agi.ops.domain.ErrorCode#REPORT_INVALID}.</p>
 */
public final class DiagnosisValidator {

    public static final int MAX_TITLE_LENGTH = 300;
    public static final int MAX_SUMMARY_LENGTH = 4000;
    public static final int MAX_STATEMENT_LENGTH = 2000;

    /** Marker ausfuehrbarer Inhalte; Pruefung ohne Beachtung der Gross-/Kleinschreibung. */
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "<script", "<iframe", "javascript:", "data:text/html", "<img");

    public DiagnosisValidator() {
    }

    /** Volle Validierung gegen den gebundenen Snapshot inklusive Belegreferenzen und Zeilenbereichen. */
    public ReportValidationResult validate(DiagnosisDocument document, StoredSnapshot snapshot) {
        if (document == null) {
            return ReportValidationResult.failed(
                    List.of(ReportValidationResult.error("diagnosis", "Der Berichtsentwurf fehlt.")), List.of());
        }
        if (snapshot == null || snapshot.manifest() == null) {
            return ReportValidationResult.failed(
                    List.of(ReportValidationResult.error("snapshot", "Der gebundene Snapshot fehlt.")), List.of());
        }
        List<OpsError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkStructure(document, errors, warnings);
        checkSnapshotBinding(document.snapshotId(), snapshot.manifest().snapshotId(), errors);
        checkEvidence(document, snapshot, errors);
        checkReferences(document, errors);
        checkAssessment(document, snapshot.manifest(), errors, warnings);
        return result(errors, warnings);
    }

    /**
     * Validierung ohne Snapshot: Struktur, Textgrenzen, ausfuehrbare Inhalte und die
     * Statusregeln, die kein Manifest benoetigen. Wird beim erneuten Lesen eines
     * gespeicherten Berichts verwendet, wenn kein Manifest vorliegt.
     */
    public ReportValidationResult validateStructure(DiagnosisDocument document) {
        if (document == null) {
            return ReportValidationResult.failed(
                    List.of(ReportValidationResult.error("diagnosis", "Der Berichtsentwurf fehlt.")), List.of());
        }
        List<OpsError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkStructure(document, errors, warnings);
        return result(errors, warnings);
    }

    /** Validierung ohne Manifest, aber mit pruefbarer Snapshotbindung an eine bekannte ID. */
    public ReportValidationResult validateBinding(DiagnosisDocument document, SnapshotId snapshotId) {
        if (snapshotId == null) {
            return ReportValidationResult.failed(
                    List.of(ReportValidationResult.error("snapshotId", "Die erwartete Snapshot-ID fehlt.")), List.of());
        }
        if (document == null) {
            return ReportValidationResult.failed(
                    List.of(ReportValidationResult.error("diagnosis", "Der Berichtsentwurf fehlt.")), List.of());
        }
        List<OpsError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkStructure(document, errors, warnings);
        checkSnapshotBinding(document.snapshotId(), snapshotId.value(), errors);
        return result(errors, warnings);
    }

    private static void checkStructure(DiagnosisDocument document, List<OpsError> errors, List<String> warnings) {
        if (document.schemaVersion() != DiagnosisDocument.SCHEMA_VERSION) {
            errors.add(ReportValidationResult.error("schemaVersion", "schemaVersion muss "
                    + DiagnosisDocument.SCHEMA_VERSION + " sein (gefunden: " + document.schemaVersion() + ")."));
        }
        if (document.language() == null || document.language().isBlank()) {
            errors.add(ReportValidationResult.error("language", "language darf nicht leer sein."));
        }
        checkText("title", document.title(), MAX_TITLE_LENGTH, errors);
        checkText("summary", document.summary(), MAX_SUMMARY_LENGTH, errors);

        for (int i = 0; i < document.facts().size(); i++) {
            Fact fact = document.facts().get(i);
            checkText("facts[" + i + "].statement", fact.statement(), MAX_STATEMENT_LENGTH, errors);
            if (fact.evidence().isEmpty()) {
                errors.add(ReportValidationResult.error("facts[" + i + "].evidence",
                        "Ein Fakt braucht mindestens eine Belegreferenz."));
            }
        }

        for (int i = 0; i < document.hypotheses().size(); i++) {
            Hypothesis hypothesis = document.hypotheses().get(i);
            String prefix = "hypotheses[" + i + "]";
            checkText(prefix + ".category", hypothesis.category(), 0, errors);
            checkText(prefix + ".statement", hypothesis.statement(), MAX_STATEMENT_LENGTH, errors);
            for (int j = 0; j < hypothesis.limitations().size(); j++) {
                checkText(prefix + ".limitations[" + j + "]", hypothesis.limitations().get(j), 0, errors);
            }
            if (hypothesis.strength() == Strength.SUPPORTED && hypothesis.supports().isEmpty()) {
                warnings.add("Hypothese " + hypothesis.id()
                        + " ist als supported eingestuft, stuetzt sich aber auf keine Fact-ID.");
            }
        }

        for (int i = 0; i < document.timeline().size(); i++) {
            TimelineEntry entry = document.timeline().get(i);
            String prefix = "timeline[" + i + "]";
            checkText(prefix + ".description", entry.description(), 0, errors);
            if (entry.at() == null) {
                if (entry.timeBasis() == null || entry.timeBasis().isBlank()
                        || "unknown".equalsIgnoreCase(entry.timeBasis())) {
                    warnings.add("Zeitleiste " + (i + 1)
                            + ": Der Zeitpunkt ist nicht belegt und die Zeitbasis fehlt; es wird kein Zeitstempel erfunden.");
                }
            } else if (!isRfc3339Instant(entry.at())) {
                errors.add(ReportValidationResult.error(prefix + ".at",
                        "Der Zeitpunkt '" + clip(entry.at()) + "' ist kein RFC3339-Instant."));
            }
        }

        for (int i = 0; i < document.recommendations().size(); i++) {
            Recommendation recommendation = document.recommendations().get(i);
            String prefix = "recommendations[" + i + "]";
            checkText(prefix + ".description", recommendation.description(), 0, errors);
            checkText(prefix + ".rationale", recommendation.rationale(), 0, errors);
            checkText(prefix + ".risk", recommendation.risk(), 0, errors);
            checkText(prefix + ".verification", recommendation.verification(), 0, errors);
            if (recommendation.executed()) {
                errors.add(ReportValidationResult.error(prefix + ".executed",
                        "Handlungsvorschlaege duerfen in V1 nicht ausgefuehrt werden (executed muss false sein)."));
            }
        }

        for (int i = 0; i < document.openQuestions().size(); i++) {
            checkText("openQuestions[" + i + "]", document.openQuestions().get(i), 0, errors);
        }
        for (int i = 0; i < document.limitations().size(); i++) {
            checkText("limitations[" + i + "]", document.limitations().get(i), 0, errors);
        }

        if (!document.actionsTaken().isEmpty()) {
            errors.add(ReportValidationResult.error("actionsTaken",
                    "actionsTaken muss in V1 leer bleiben; der Agent hat keine Aenderungen ausgefuehrt."));
        }
    }

    private static void checkSnapshotBinding(String documentSnapshotId, String boundSnapshotId, List<OpsError> errors) {
        if (documentSnapshotId == null || !documentSnapshotId.equals(boundSnapshotId)) {
            errors.add(ReportValidationResult.error("snapshotId", "Snapshotbindung: Der Bericht verweist auf '"
                    + clip(documentSnapshotId) + "', gebunden ist '" + clip(boundSnapshotId)
                    + "'. Ein Bericht kann keinen fremden Snapshot referenzieren."));
        }
    }

    private static void checkEvidence(DiagnosisDocument document, StoredSnapshot snapshot, List<OpsError> errors) {
        for (int i = 0; i < document.facts().size(); i++) {
            Fact fact = document.facts().get(i);
            for (int j = 0; j < fact.evidence().size(); j++) {
                checkEvidenceRef(fact.evidence().get(j), "facts[" + i + "].evidence[" + j + "]", snapshot, errors);
            }
        }
        for (int i = 0; i < document.hypotheses().size(); i++) {
            Hypothesis hypothesis = document.hypotheses().get(i);
            for (int j = 0; j < hypothesis.counterEvidence().size(); j++) {
                checkEvidenceRef(hypothesis.counterEvidence().get(j),
                        "hypotheses[" + i + "].counterEvidence[" + j + "]", snapshot, errors);
            }
        }
        for (int i = 0; i < document.timeline().size(); i++) {
            TimelineEntry entry = document.timeline().get(i);
            for (int j = 0; j < entry.evidence().size(); j++) {
                checkEvidenceRef(entry.evidence().get(j), "timeline[" + i + "].evidence[" + j + "]", snapshot, errors);
            }
        }
    }

    private static void checkEvidenceRef(EvidenceRef ref, String field, StoredSnapshot snapshot, List<OpsError> errors) {
        EvidenceIndexEntry entry = snapshot.evidence(ref.evidenceId());
        if (entry == null) {
            errors.add(ReportValidationResult.error(field, "Unbekannte Evidence-ID '" + clip(ref.evidenceId())
                    + "' im gebundenen Snapshot " + clip(snapshot.manifest().snapshotId()) + "."));
            return;
        }
        int lineCount = entry.lineCount();
        Integer start = ref.startLine();
        Integer end = ref.endLine();
        if (start != null && (start < 1 || start > lineCount)) {
            errors.add(ReportValidationResult.error(field + ".startLine", "startLine " + start
                    + " liegt ausserhalb der Evidence " + clip(ref.evidenceId()) + " (1.." + lineCount + ")."));
        }
        if (end != null && (end < 1 || end > lineCount)) {
            errors.add(ReportValidationResult.error(field + ".endLine", "endLine " + end
                    + " liegt ausserhalb der Evidence " + clip(ref.evidenceId()) + " (1.." + lineCount + ")."));
        }
        if (start != null && end != null && start > end) {
            errors.add(ReportValidationResult.error(field, "startLine " + start
                    + " ist groesser als endLine " + end + "."));
        }
    }

    private static void checkReferences(DiagnosisDocument document, List<OpsError> errors) {
        Set<String> factIds = new LinkedHashSet<>();
        for (Fact fact : document.facts()) {
            factIds.add(fact.id());
        }
        Set<String> hypothesisIds = new LinkedHashSet<>();
        for (Hypothesis hypothesis : document.hypotheses()) {
            hypothesisIds.add(hypothesis.id());
        }
        for (int i = 0; i < document.hypotheses().size(); i++) {
            Hypothesis hypothesis = document.hypotheses().get(i);
            for (String support : hypothesis.supports()) {
                if (!factIds.contains(support)) {
                    errors.add(ReportValidationResult.error("hypotheses[" + i + "].supports",
                            "Unbekannte Fact-ID '" + clip(support) + "' in supports."));
                }
            }
        }
        for (int i = 0; i < document.recommendations().size(); i++) {
            Recommendation recommendation = document.recommendations().get(i);
            for (String hypothesisId : recommendation.basedOnHypotheses()) {
                if (!hypothesisIds.contains(hypothesisId)) {
                    errors.add(ReportValidationResult.error("recommendations[" + i + "].basedOnHypotheses",
                            "Unbekannte Hypothesen-ID '" + clip(hypothesisId) + "' in basedOnHypotheses."));
                }
            }
        }
    }

    /**
     * Statusregeln zur Gesamtbewertung. {@code cause_identified} verlangt eine gestuetzte
     * Hypothese und Links, die eine Kausalaussage zulassen; {@code insufficient_evidence} und
     * {@code no_failure_evidenced} sind ausdruecklich gueltig, auch ohne Facts.
     */
    private static void checkAssessment(DiagnosisDocument document, SnapshotManifest manifest,
            List<OpsError> errors, List<String> warnings) {
        AssessmentStatus assessment = document.assessment();
        if (assessment == AssessmentStatus.CAUSE_IDENTIFIED) {
            boolean supported = document.hypotheses().stream()
                    .anyMatch(hypothesis -> hypothesis.strength() == Strength.SUPPORTED);
            if (!supported) {
                errors.add(ReportValidationResult.error("assessment",
                        "cause_identified verlangt mindestens eine Hypothese mit der Belegstaerke supported."));
            }
            List<String> blockingLinks = new ArrayList<>();
            for (SnapshotLink link : manifest.links()) {
                if (link.strength() == null || !link.strength().allowsCausalClaim()) {
                    blockingLinks.add(describeLink(link));
                }
            }
            if (!blockingLinks.isEmpty()) {
                errors.add(ReportValidationResult.error("assessment",
                        "cause_identified ist bei Links ungleich exact unzulaessig: "
                                + joinLimited(blockingLinks, 3) + "."));
            }
        }
        if (assessment == AssessmentStatus.CAUSE_IDENTIFIED || assessment == AssessmentStatus.PROBABLE_CAUSE) {
            CollectionStatus status = manifest.collectionStatus();
            if (status != null && status != CollectionStatus.COMPLETE) {
                warnings.add("Der Snapshot ist nicht vollstaendig erfasst (" + status.wireName()
                        + "); die Bewertung " + assessment.wireName()
                        + " stuetzt sich damit auf eine lueckenhafte Erfassung.");
            }
        }
    }

    private static void checkText(String field, String text, int maxLength, List<OpsError> errors) {
        if (text == null) {
            return;
        }
        if (maxLength > 0 && text.length() > maxLength) {
            errors.add(ReportValidationResult.error(field, "Der Text in " + field + " ist zu lang: "
                    + text.length() + " Zeichen, erlaubt sind " + maxLength + "."));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (lower.contains(marker)) {
                errors.add(ReportValidationResult.error(field, "Der Text in " + field
                        + " enthaelt ausfuehrbaren Inhalt ('" + marker
                        + "'); Berichte duerfen kein HTML und kein Script enthalten."));
                return;
            }
        }
    }

    private static boolean isRfc3339Instant(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String candidate = value.trim();
        if (candidate.endsWith("z")) {
            candidate = candidate.substring(0, candidate.length() - 1) + "Z";
        }
        try {
            Instant.parse(candidate);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String describeLink(SnapshotLink link) {
        String strength = link.strength() == null ? "unbekannt"
                : link.strength().name().toLowerCase(Locale.ROOT);
        return clip(link.from()) + " -> " + clip(link.to()) + " (" + strength + ")";
    }

    private static String joinLimited(List<String> values, int limit) {
        if (values.size() <= limit) {
            return String.join("; ", values);
        }
        return String.join("; ", values.subList(0, limit)) + "; ... (" + values.size() + " Links)";
    }

    /** Kuerzt einen Fremdwert fuer eine Fehlermeldung und entfernt Steuerzeichen. */
    private static String clip(String value) {
        if (value == null) {
            return "<leer>";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), 84));
        for (int i = 0; i < value.length() && sb.length() < 80; i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        String singleLine = sb.toString().trim();
        return value.length() > 80 ? singleLine + "..." : singleLine;
    }

    private static ReportValidationResult result(List<OpsError> errors, List<String> warnings) {
        return errors.isEmpty()
                ? ReportValidationResult.ok(warnings)
                : ReportValidationResult.failed(errors, warnings);
    }
}
