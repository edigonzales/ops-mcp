package ch.so.agi.ops.domain;

/** Gesamtbewertung eines Diagnoseberichts. */
public enum AssessmentStatus {
    CAUSE_IDENTIFIED("cause_identified"),
    PROBABLE_CAUSE("probable_cause"),
    MULTIPLE_HYPOTHESES("multiple_hypotheses"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    NO_FAILURE_EVIDENCED("no_failure_evidenced");

    private final String wireName;

    AssessmentStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static AssessmentStatus fromWireName(String value) {
        for (AssessmentStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unbekannte Bewertung: " + value);
    }
}
