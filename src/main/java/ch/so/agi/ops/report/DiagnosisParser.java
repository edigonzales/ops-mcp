package ch.so.agi.ops.report;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Liest einen Diagnosebericht nach {@code diagnosis-v1} aus JSON.
 *
 * <p>Der Parser ist strikt und abweisend: ungueltiges JSON, eine Ueberschreitung des
 * Bytegrenzwerts, unbekannte Felder, fehlende Pflichtfelder und unbekannte Enumwerte fuer
 * {@code assessment} und {@code strength} werden mit {@link ErrorCode#REPORT_INVALID}
 * abgewiesen. Es werden <em>keine</em> fehlenden Angaben erfunden und keine unbekannten
 * Felder stillschweigend verworfen (FAIL_ON_UNKNOWN_PROPERTIES).</p>
 *
 * <p>Der Parser prueft nur die Form. Ob ein Bericht fachlich zulaessig ist (zum Beispiel
 * {@code executed: false} oder {@code actionsTaken} leer), entscheidet
 * {@link DiagnosisValidator}. Insbesondere wird {@code executed: true} geparst und erst
 * danach abgewiesen, damit der Fehler als Validierungsfehler sichtbar wird.</p>
 */
public final class DiagnosisParser {

    /**
     * Strikte Ableitung des zentralen JSON-Mappers aus {@link YamlSupport#jsonMapper()}.
     * Die Parser-Grenzen (Tiefe, Zeichenkettenlaenge, Dokumentgroesse) bleiben erhalten.
     */
    private static final JsonMapper STRICT_MAPPER = YamlSupport.jsonMapper().rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Felder der obersten Ebene nach {@code diagnosis-v1}. */
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "snapshotId", "language", "assessment", "title", "summary",
            "facts", "hypotheses", "timeline", "recommendations", "openQuestions", "limitations",
            "actionsTaken");

    /** Pflichtfelder der obersten Ebene. */
    private static final List<String> REQUIRED_FIELDS = List.of(
            "schemaVersion", "snapshotId", "language", "assessment", "title", "summary");

    /** Felder, die Listen sind, wenn sie gesetzt werden. */
    private static final List<String> LIST_FIELDS = List.of(
            "facts", "hypotheses", "timeline", "recommendations", "openQuestions", "limitations",
            "actionsTaken");

    /** Felder, die Zeichenketten sind. */
    private static final List<String> TEXT_FIELDS = List.of("snapshotId", "language", "title", "summary");

    private DiagnosisParser() {
    }

    /** Liest einen Berichtsentwurf aus UTF-8-Bytes. */
    public static DiagnosisDocument parse(byte[] json, int maxBytes) {
        int limit = byteLimit(maxBytes);
        if (json == null || json.length == 0) {
            throw invalid("diagnosis", "Der Berichtsentwurf fehlt oder ist leer.");
        }
        if (json.length > limit) {
            throw invalid("diagnosis", "Der Berichtsentwurf ist zu gross: " + json.length
                    + " Bytes, erlaubt sind hoechstens " + limit + " Bytes.");
        }
        try {
            return convert(STRICT_MAPPER.readTree(json));
        } catch (JacksonException e) {
            throw invalid("diagnosis", "Der Berichtsentwurf ist kein gueltiges JSON: "
                    + YamlSupport.describeParseError(e));
        }
    }

    /** Liest einen Berichtsentwurf aus einer Zeichenkette; gemessen werden die UTF-8-Bytes. */
    public static DiagnosisDocument parse(String json, int maxBytes) {
        if (json == null) {
            throw invalid("diagnosis", "Der Berichtsentwurf fehlt.");
        }
        return parse(json.getBytes(StandardCharsets.UTF_8), maxBytes);
    }

    private static DiagnosisDocument convert(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw invalid("diagnosis", "Der Berichtsentwurf fehlt oder ist leer.");
        }
        if (!root.isObject()) {
            throw invalid("diagnosis", "Der Berichtsentwurf muss ein JSON-Objekt sein.");
        }
        ObjectNode node = (ObjectNode) root;
        checkKnownFields(node);
        checkRequiredFields(node);
        checkFieldTypes(node);
        checkNestedRequiredFields(node);
        translateWireNames(node);
        try {
            return STRICT_MAPPER.treeToValue(node, DiagnosisDocument.class);
        } catch (UnrecognizedPropertyException e) {
            throw invalid(e.getPropertyName(), "Unbekanntes Feld im Berichtsentwurf: "
                    + shorten(e.getPropertyName()) + ".");
        } catch (JacksonException | IllegalArgumentException e) {
            throw invalid("diagnosis", "Der Berichtsentwurf entspricht nicht dem Diagnosisvertrag: "
                    + YamlSupport.describeParseError(e));
        }
    }

    private static void checkKnownFields(ObjectNode node) {
        List<String> unknown = new ArrayList<>();
        for (String field : node.propertyNames()) {
            // Der Bericht ist ein geschlossener Vertrag: unbekannte Felder werden abgewiesen,
            // auch namespacete x--Felder. Die Erweiterbarkeit betrifft das Domaenenmodell
            // (Felder wie useCase und generische Evidence-Typen) und die Konfiguration, nicht
            // den Berichtsentwurf. Diese Entscheidung ist bewusst strenger als das Schema.
            if (!TOP_LEVEL_FIELDS.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw invalid(unknown.getFirst(), "Unbekannte Felder im Berichtsentwurf: "
                    + String.join(", ", unknown.stream().map(DiagnosisParser::shorten).toList()) + ".");
        }
    }

    private static void checkRequiredFields(ObjectNode node) {
        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw invalid("diagnosis", "Pflichtfelder fehlen oder sind null: "
                    + String.join(", ", missing) + ".");
        }
    }

    private static void checkFieldTypes(ObjectNode node) {
        if (!node.get("schemaVersion").isIntegralNumber()) {
            throw invalid("schemaVersion", "schemaVersion muss die Zahl "
                    + DiagnosisDocument.SCHEMA_VERSION + " sein.");
        }
        for (String field : TEXT_FIELDS) {
            if (!node.get(field).isString()) {
                throw invalid(field, "Das Feld " + field + " muss eine Zeichenkette sein.");
            }
        }
        if (!node.get("assessment").isString()) {
            throw invalid("assessment", "Das Feld assessment muss eine Zeichenkette sein.");
        }
        for (String field : LIST_FIELDS) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.isArray()) {
                throw invalid(field, "Das Feld " + field + " muss eine Liste sein.");
            }
        }
    }

    /**
     * Prueft Pflichtfelder der verschachtelten Objekte. Fehlende Angaben werden hier
     * abgewiesen, damit keine stillen Standardwerte entstehen; die Meldungen sind bewusst
     * deutsch und nennen den Pfad im Dokument.
     */
    private static void checkNestedRequiredFields(ObjectNode node) {
        JsonNode facts = node.get("facts");
        if (facts != null && facts.isArray()) {
            for (int i = 0; i < facts.size(); i++) {
                JsonNode fact = facts.get(i);
                if (!fact.isObject()) {
                    continue;
                }
                requireText(fact, "facts[" + i + "]", "id");
                requireText(fact, "facts[" + i + "]", "statement");
                checkEvidenceRefs(fact.get("evidence"), "facts[" + i + "].evidence");
            }
        }
        JsonNode hypotheses = node.get("hypotheses");
        if (hypotheses != null && hypotheses.isArray()) {
            for (int i = 0; i < hypotheses.size(); i++) {
                JsonNode hypothesis = hypotheses.get(i);
                if (!hypothesis.isObject()) {
                    continue;
                }
                requireText(hypothesis, "hypotheses[" + i + "]", "id");
                requireText(hypothesis, "hypotheses[" + i + "]", "category");
                requireText(hypothesis, "hypotheses[" + i + "]", "statement");
                requireText(hypothesis, "hypotheses[" + i + "]", "strength");
                checkEvidenceRefs(hypothesis.get("counterEvidence"), "hypotheses[" + i + "].counterEvidence");
            }
        }
        JsonNode timeline = node.get("timeline");
        if (timeline != null && timeline.isArray()) {
            for (int i = 0; i < timeline.size(); i++) {
                JsonNode entry = timeline.get(i);
                if (!entry.isObject()) {
                    continue;
                }
                requireText(entry, "timeline[" + i + "]", "description");
                checkEvidenceRefs(entry.get("evidence"), "timeline[" + i + "].evidence");
            }
        }
        JsonNode recommendations = node.get("recommendations");
        if (recommendations != null && recommendations.isArray()) {
            for (int i = 0; i < recommendations.size(); i++) {
                JsonNode entry = recommendations.get(i);
                if (!entry.isObject()) {
                    continue;
                }
                requireText(entry, "recommendations[" + i + "]", "description");
            }
        }
    }

    private static void checkEvidenceRefs(JsonNode refs, String path) {
        if (refs == null || refs.isNull() || !refs.isArray()) {
            return;
        }
        for (int i = 0; i < refs.size(); i++) {
            JsonNode ref = refs.get(i);
            if (!ref.isObject()) {
                continue;
            }
            JsonNode evidenceId = ref.get("evidenceId");
            if (evidenceId == null || evidenceId.isNull() || !evidenceId.isString() || evidenceId.stringValue().isBlank()) {
                throw invalid(path + "[" + i + "].evidenceId",
                        "Das Pflichtfeld evidenceId fehlt oder ist keine Zeichenkette.");
            }
        }
    }

    private static void requireText(JsonNode owner, String path, String field) {
        JsonNode value = owner.get(field);
        if (value == null || value.isNull() || !value.isString() || value.stringValue().isBlank()) {
            throw invalid(path + "." + field, "Das Pflichtfeld " + path + "." + field
                    + " fehlt oder ist keine nicht leere Zeichenkette.");
        }
    }

    /**
     * Uebersetzt die Wire-Namen der Enums in die Java-Namen der Enums. Unbekannte Werte
     * werden abgewiesen, damit keine stillen Standardwerte entstehen.
     */
    private static void translateWireNames(ObjectNode node) {
        String rawAssessment = node.get("assessment").stringValue();
        try {
            node.put("assessment", AssessmentStatus.fromWireName(rawAssessment).name());
        } catch (IllegalArgumentException e) {
            throw invalid("assessment", "Unbekannte Bewertung '" + shorten(rawAssessment)
                    + "'. Erlaubt: " + allowedAssessments() + ".");
        }
        JsonNode hypotheses = node.get("hypotheses");
        if (hypotheses == null || !hypotheses.isArray()) {
            return;
        }
        for (int i = 0; i < hypotheses.size(); i++) {
            JsonNode hypothesis = hypotheses.get(i);
            if (!hypothesis.isObject()) {
                continue;
            }
            JsonNode strength = hypothesis.get("strength");
            if (strength == null || strength.isNull() || !strength.isString()) {
                continue;
            }
            String rawStrength = strength.stringValue();
            try {
                ((ObjectNode) hypothesis).put("strength", Strength.fromWireName(rawStrength).name());
            } catch (IllegalArgumentException e) {
                throw invalid("hypotheses[" + i + "].strength", "Unbekannte Belegstaerke '"
                        + shorten(rawStrength) + "'. Erlaubt: " + allowedStrengths() + ".");
            }
        }
    }

    private static String allowedAssessments() {
        return Arrays.stream(AssessmentStatus.values())
                .map(AssessmentStatus::wireName)
                .collect(Collectors.joining(", "));
    }

    private static String allowedStrengths() {
        return Arrays.stream(Strength.values())
                .map(Strength::wireName)
                .collect(Collectors.joining(", "));
    }

    private static int byteLimit(int maxBytes) {
        if (maxBytes <= 0) {
            return DiagnosisDocument.MAX_DRAFT_BYTES;
        }
        return Math.min(maxBytes, DiagnosisDocument.MAX_DRAFT_BYTES);
    }

    private static ReportException invalid(String field, String message) {
        return new ReportException(ErrorCode.REPORT_INVALID, message,
                List.of(ReportValidationResult.error(field, message)));
    }

    /** Kuerzt einen Fremdwert fuer eine Fehlermeldung und entfernt Steuerzeichen. */
    private static String shorten(String value) {
        if (value == null) {
            return "<leer>";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), 84));
        for (int i = 0; i < value.length() && sb.length() < 80; i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        String singleLine = sb.toString().trim();
        if (value.length() > 80) {
            singleLine = singleLine + "...";
        }
        return singleLine;
    }
}
