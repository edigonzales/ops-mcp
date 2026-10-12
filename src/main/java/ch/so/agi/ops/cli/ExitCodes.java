package ch.so.agi.ops.cli;

/**
 * Exitcodes nach SCHNITTSTELLEN.md Abschnitt 2.2.
 *
 * <p>{@code 0} bedeutet technisch erfolgreich; der Daten-/Diagnosestatus steht zusaetzlich im
 * Ergebnis. Ein korrekt gespeicherter Bericht mit {@code assessment: insufficient_evidence}
 * kann Exitcode 0 haben – das ist keine Behauptung, die Ursache sei gefunden.</p>
 */
public final class ExitCodes {

    /** Befehl technisch erfolgreich. */
    public static final int OK = 0;
    /** Ungueltige Eingabe oder unvollstaendige/ungueltige Konfiguration. */
    public static final int INVALID_INPUT_OR_CONFIG = 2;
    /** Quellzugriff fehlgeschlagen; keine verwendbare Untersuchung moeglich. */
    public static final int SOURCE_UNAVAILABLE = 3;
    /** Partieller Snapshot erzeugt; mindestens eine geplante Quelle oder Budgetpruefung unvollstaendig. */
    public static final int PARTIAL_SNAPSHOT = 4;
    /** Berichtsschema, Snapshotbindung oder Belegreferenzen ungueltig. */
    public static final int REPORT_INVALID = 5;
    /** Sicherheits-/Scope-Verletzung abgewehrt. */
    public static final int SCOPE_VIOLATION = 6;

    private ExitCodes() {
    }

    public static String describe(int code) {
        return switch (code) {
            case OK -> "erfolgreich";
            case INVALID_INPUT_OR_CONFIG -> "ungueltige Eingabe oder Konfiguration";
            case SOURCE_UNAVAILABLE -> "Quellzugriff fehlgeschlagen";
            case PARTIAL_SNAPSHOT -> "partieller Snapshot";
            case REPORT_INVALID -> "Bericht ungueltig";
            case SCOPE_VIOLATION -> "Sicherheits-/Scope-Verletzung";
            default -> "unbekannter Code";
        };
    }
}
