package ch.so.agi.ops.domain;

/** Qualitativer Status der geplanten begrenzten Erfassung. */
public enum CollectionStatus {
    /** Alle geplanten Quellen wurden ohne technische Luecke erfasst. */
    COMPLETE("complete"),
    /** Mindestens eine geplante Quelle oder Budgetpruefung ist unvollstaendig. */
    PARTIAL("partial"),
    /** Es konnte keine verwertbare Untersuchung aufgebaut werden. */
    FAILED("failed");

    private final String wireName;

    CollectionStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static CollectionStatus fromWireName(String value) {
        for (CollectionStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unbekannter Sammelstatus: " + value);
    }
}
