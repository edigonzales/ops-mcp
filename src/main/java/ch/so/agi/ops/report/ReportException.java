package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

import java.util.List;
import java.util.Objects;

/**
 * Fachlicher Fehler beim Lesen, Validieren oder Speichern eines Diagnoseberichts.
 *
 * <p>Der Fehlercode ist Teil des Fehlervertrags aus {@code SCHNITTSTELLEN.md} Abschnitt 7.
 * Ungueltige Berichtsentwuerfe werden immer mit {@link ErrorCode#REPORT_INVALID} abgewiesen;
 * ein unzulaessiger Pfad oder eine unzulaessige Kennung mit {@link ErrorCode#SCOPE_DENIED}
 * beziehungsweise {@link ErrorCode#NOT_FOUND}, wenn ein Bericht nicht vorhanden ist.</p>
 *
 * <p>Die mitgefuehrten {@link OpsError}-Eintraege sind bereits redigiert und enthalten keine
 * Berichtstexte in ungekuerzter Form.</p>
 */
public class ReportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<OpsError> errors;

    public ReportException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ReportException(ErrorCode code, String message, List<OpsError> errors) {
        super(message == null || message.isBlank() ? "Ungueltiger Diagnosebericht." : message);
        this.code = Objects.requireNonNull(code, "code");
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Stabiler Fehlercode aus dem Fehlervertrag. */
    public ErrorCode code() {
        return code;
    }

    /** Strukturierte Einzelfehler der Validierung; leer, wenn keine vorliegen. */
    public List<OpsError> errors() {
        return errors;
    }
}
