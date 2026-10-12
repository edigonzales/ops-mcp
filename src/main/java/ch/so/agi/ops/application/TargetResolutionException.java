package ch.so.agi.ops.application;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

/** Ziel konnte nicht eindeutig aufgeloest werden. */
public class TargetResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient OpsError error;

    public TargetResolutionException(ErrorCode code, String message) {
        this(code, message, new OpsError(code, "target", message, false,
                "Fehlende oder unklare Angaben mit dem Betreiber klaeren."));
    }

    public TargetResolutionException(ErrorCode code, String message, OpsError error) {
        super(message);
        this.code = code;
        this.error = error;
    }

    public ErrorCode code() {
        return code;
    }

    public OpsError error() {
        return error;
    }
}
