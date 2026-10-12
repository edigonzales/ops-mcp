package ch.so.agi.ops.domain;

/**
 * Betriebsmodus des Agenten. Ein Modus ist entweder {@code fixture} oder {@code live}
 * und wird ausschliesslich explizit aus der gewaehlten Konfigurationsdatei uebernommen.
 * Es gibt keine automatische Umschaltung bei Fehlern.
 */
public enum DataMode {
    FIXTURE("fixture"),
    LIVE("live");

    private final String wireName;

    DataMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static DataMode fromWireName(String value) {
        for (DataMode mode : values()) {
            if (mode.wireName.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unbekannter Modus: " + value);
    }
}
