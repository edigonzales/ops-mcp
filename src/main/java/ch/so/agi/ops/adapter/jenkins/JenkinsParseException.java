package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.domain.ErrorCode;

import java.util.Objects;

/**
 * Fehler beim Auswerten einer Jenkins-Antwort. Die Meldung ist bereits redigiert und enthaelt
 * weder Zugangsdaten noch rohe Antwortkoerper. Der stabile Fehlercode stammt aus dem
 * Fehlervertrag in {@code docs/spec-v1/SCHNITTSTELLEN.md} Abschnitt 7.
 *
 * <p>Typische Faelle: ungueltiges JSON ({@code SOURCE_PARSE_ERROR}), Login-HTML statt JSON
 * ({@code SOURCE_PARSE_ERROR}) und ein fehlendes {@code lastFailed}-Feld ({@code NOT_FOUND}).</p>
 */
public class JenkinsParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public JenkinsParseException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public JenkinsParseException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** Stabiler Fehlercode aus dem Fehlervertrag. */
    public ErrorCode code() {
        return code;
    }
}
