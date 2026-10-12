package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

import java.util.List;

/**
 * Ergebnis der Berichtsvalidierung.
 *
 * <p>Wichtig: Die Validierung bestaetigt nur Struktur, Snapshotbindung und
 * Referenzexistenz. Sie bestaetigt <em>nicht</em> automatisch die Wahrheit oder Kausalitaet
 * der Aussagen des Modells.</p>
 *
 * @param valid    true, wenn keine Fehler gefunden wurden
 * @param errors   abweisende Fehler
 * @param warnings Hinweise, die den Bericht nicht blockieren
 */
public record ReportValidationResult(boolean valid, List<OpsError> errors, List<String> warnings) {

    public ReportValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ReportValidationResult ok(List<String> warnings) {
        return new ReportValidationResult(true, List.of(), warnings);
    }

    public static ReportValidationResult failed(List<OpsError> errors, List<String> warnings) {
        return new ReportValidationResult(false, errors, warnings);
    }

    public static OpsError error(String field, String message) {
        return new OpsError(ErrorCode.REPORT_INVALID, field, message, false,
                "Den Berichtsentwurf korrigieren und erneut speichern.");
    }

    public static OpsError error(ErrorCode code, String field, String message) {
        return new OpsError(code, field, message, false,
                "Den Berichtsentwurf korrigieren und erneut speichern.");
    }
}
