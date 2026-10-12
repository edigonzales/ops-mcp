package ch.so.agi.ops.security;

import ch.so.agi.ops.domain.ErrorCode;

/** Sicherheits-/Scope-Verletzung. Fuehrt zum Exitcode 6 und wird nie stillschweigend geschluckt. */
public class ScopeViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ScopeViolationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
