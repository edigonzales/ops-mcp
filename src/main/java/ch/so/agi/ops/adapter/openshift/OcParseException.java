package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.domain.ErrorCode;

import java.util.Objects;

/**
 * Fehler beim Auswerten einer {@code oc}-Rohantwort oder einer lokalen Clusterdatei.
 *
 * <p>Die Meldung enthaelt niemals Datei- oder Antwortinhalte, keine Zugangsdaten und keinen
 * vollstaendigen Pfad. Sie beschreibt nur, welche Struktur erwartet wurde und warum die
 * Antwort nicht verwertbar ist.</p>
 */
public class OcParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public OcParseException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public OcParseException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** Stabiler Fehlercode aus dem Fehlervertrag. */
    public ErrorCode code() {
        return code;
    }
}
