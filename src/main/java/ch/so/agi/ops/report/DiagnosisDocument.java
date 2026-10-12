package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;

import java.util.List;
import java.util.Objects;

/**
 * Strukturierter Diagnosebericht nach {@code diagnosis-v1.schema.json}.
 *
 * <p>Jede umgebungsspezifische Tatsachenbehauptung stuetzt sich auf Evidence oder auf bereits
 * belegte Fact-IDs. Allgemeines Runbookwissen ist kein Beleg dafuer, dass ein Ereignis
 * tatsaechlich stattgefunden hat.</p>
 *
 * @param schemaVersion   immer {@code 1}
 * @param snapshotId      gebundener Snapshot; ein Bericht kann keinen fremden Snapshot referenzieren
 * @param language        Sprache des Berichts, in V1 {@code de-CH}
 * @param assessment      Gesamtbewertung
 * @param title           Kurzer Titel
 * @param summary         Zusammenfassung
 * @param facts           belegte Tatsachen
 * @param hypotheses      konkurrierende Erklaerungen mit qualitativer Belegstaerke
 * @param timeline        Ereignisfolge; {@code at} darf {@code null} sein
 * @param recommendations Vorschlaege, immer mit {@code executed: false}
 * @param openQuestions   offene Punkte
 * @param limitations     bekannte Grenzen der Untersuchung
 * @param actionsTaken    in V1 immer leer
 */
public record DiagnosisDocument(
        int schemaVersion,
        String snapshotId,
        String language,
        AssessmentStatus assessment,
        String title,
        String summary,
        List<Fact> facts,
        List<Hypothesis> hypotheses,
        List<TimelineEntry> timeline,
        List<Recommendation> recommendations,
        List<String> openQuestions,
        List<String> limitations,
        List<String> actionsTaken) {

    public static final int SCHEMA_VERSION = 1;
    public static final String LANGUAGE_DE_CH = "de-CH";
    /** Obergrenze eines Berichtsentwurfs. */
    public static final int MAX_DRAFT_BYTES = 256 * 1024;

    public DiagnosisDocument {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        facts = facts == null ? List.of() : List.copyOf(facts);
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        actionsTaken = actionsTaken == null ? List.of() : List.copyOf(actionsTaken);
    }

    /**
     * Belegte Tatsache.
     *
     * @param id        Kennung, z. B. {@code F-1}
     * @param statement Tatsachenbehauptung
     * @param evidence  Belegreferenzen; mindestens eine ist erforderlich
     */
    public record Fact(String id, String statement, List<EvidenceRef> evidence) {

        public Fact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(statement, "statement");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", id);
            map.put("statement", statement);
            map.put("evidence", evidence.stream().map(EvidenceRef::toMap).toList());
            return map;
        }
    }

    /**
     * Erklaerungshypothese.
     *
     * @param id              Kennung, z. B. {@code H-1}
     * @param category        Kategorie aus dem Runbook, z. B. {@code startup-budget}
     * @param statement       Hypothese
     * @param strength        qualitative Belegstaerke; keine Prozentwerte
     * @param supports        Fact-IDs, die die Hypothese stuetzen
     * @param counterEvidence Evidence-Referenzen, die gegen die Hypothese sprechen
     * @param limitations     Grenzen der Hypothese
     */
    public record Hypothesis(String id, String category, String statement, Strength strength, List<String> supports,
            List<EvidenceRef> counterEvidence, List<String> limitations) {

        public Hypothesis {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(strength, "strength");
            supports = supports == null ? List.of() : List.copyOf(supports);
            counterEvidence = counterEvidence == null ? List.of() : List.copyOf(counterEvidence);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", id);
            map.put("category", category);
            map.put("statement", statement);
            map.put("strength", strength.wireName());
            map.put("supports", supports);
            map.put("counterEvidence", counterEvidence.stream().map(EvidenceRef::toMap).toList());
            map.put("limitations", limitations);
            return map;
        }
    }

    /** Qualitative Belegstaerke. */
    public enum Strength {
        SUPPORTED("supported"),
        PLAUSIBLE("plausible"),
        WEAK("weak"),
        CONTRADICTED("contradicted");

        private final String wireName;

        Strength(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Strength fromWireName(String value) {
            for (Strength strength : values()) {
                if (strength.wireName.equals(value)) {
                    return strength;
                }
            }
            throw new IllegalArgumentException("Unbekannte Belegstaerke: " + value);
        }
    }

    /**
     * Ereignis in der Zeitleiste. {@code at: null} ist zulaessig; aus Logzeilennummern,
     * Reihenfolge oder Annahmen werden keine Zeitstempel erzeugt.
     *
     * @param at          Zeitpunkt oder {@code null}
     * @param timeBasis   Herkunft der Zeitangabe, z. B. {@code event-lastTimestamp}, {@code derived}, {@code unknown}
     * @param description Beschreibung
     * @param evidence    Belegreferenzen
     */
    public record TimelineEntry(String at, String timeBasis, String description, List<EvidenceRef> evidence) {

        public TimelineEntry {
            Objects.requireNonNull(description, "description");
            timeBasis = timeBasis == null || timeBasis.isBlank() ? "unknown" : timeBasis;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("at", at);
            map.put("timeBasis", timeBasis);
            map.put("description", description);
            map.put("evidence", evidence.stream().map(EvidenceRef::toMap).toList());
            return map;
        }
    }

    /**
     * Nicht ausgeführter Korrekturvorschlag.
     *
     * @param description         Vorschlag als Text
     * @param rationale           Begruendung
     * @param basedOnHypotheses   Hypothesen-IDs, auf denen der Vorschlag beruht
     * @param risk                Risiko des Vorschlags
     * @param verification        Vorgeschlagene spaetere menschliche Verifikation
     * @param executed            in V1 immer {@code false}
     */
    public record Recommendation(String description, String rationale, List<String> basedOnHypotheses, String risk,
            String verification, boolean executed) {

        public Recommendation {
            Objects.requireNonNull(description, "description");
            basedOnHypotheses = basedOnHypotheses == null ? List.of() : List.copyOf(basedOnHypotheses);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("description", description);
            map.put("rationale", rationale);
            map.put("basedOnHypotheses", basedOnHypotheses);
            map.put("risk", risk);
            map.put("verification", verification);
            map.put("executed", executed);
            return map;
        }
    }

    /**
     * Belegreferenz mit Zeilenbereich.
     *
     * @param evidenceId Evidence-ID im gebundenen Snapshot
     * @param startLine  1-basierte Startzeile
     * @param endLine    1-basierte Endzeile
     */
    public record EvidenceRef(String evidenceId, Integer startLine, Integer endLine) {

        public EvidenceRef {
            Objects.requireNonNull(evidenceId, "evidenceId");
        }

        public static EvidenceRef of(String evidenceId) {
            return new EvidenceRef(evidenceId, null, null);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("evidenceId", evidenceId);
            if (startLine != null) {
                map.put("startLine", startLine);
            }
            if (endLine != null) {
                map.put("endLine", endLine);
            }
            return map;
        }
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("snapshotId", snapshotId);
        map.put("language", language);
        map.put("assessment", assessment.wireName());
        map.put("title", title);
        map.put("summary", summary);
        map.put("facts", facts.stream().map(Fact::toMap).toList());
        map.put("hypotheses", hypotheses.stream().map(Hypothesis::toMap).toList());
        map.put("timeline", timeline.stream().map(TimelineEntry::toMap).toList());
        map.put("recommendations", recommendations.stream().map(Recommendation::toMap).toList());
        map.put("openQuestions", openQuestions);
        map.put("limitations", limitations);
        map.put("actionsTaken", actionsTaken);
        return map;
    }
}
