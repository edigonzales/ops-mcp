package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

import java.util.Objects;

/**
 * Fehler beim <em>lesenden</em> Git-Zugriff.
 *
 * <p>Die Ausnahme traegt einen stabilen {@link ErrorCode} und eine bereits redigierte
 * Erklaerung. Sie wird vom Adapter in einen strukturierten {@link OpsError} uebersetzt und
 * niemals stillschweigend geschluckt: Ein fehlender Commit, ein unzulaessiger Pfad und ein
 * technischer Timeout sind ausdruecklich verschiedene Faelle.</p>
 *
 * <p>Es gibt keine schreibende Variante. {@link GitProcess} und {@link GitObjectReader}
 * bieten ausschliesslich lesende Git-Befehle an.</p>
 */
public class GitReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String suggestedAction;

    public GitReadException(ErrorCode code, String message) {
        this(code, message, "");
    }

    public GitReadException(ErrorCode code, String message, String suggestedAction) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.suggestedAction = suggestedAction == null ? "" : suggestedAction;
    }

    public ErrorCode code() {
        return code;
    }

    public String suggestedAction() {
        return suggestedAction;
    }

    /**
     * Uebersetzt die Ausnahme in den Fehlervertrag. {@code retryable} ist nur bei
     * tatsaechlich transienten Transportfehlern gesetzt; ein unzulaessiger Pfad oder eine
     * fehlende Revision wird nicht wiederholt.
     */
    public OpsError toError(String sourceId) {
        boolean retryable = code == ErrorCode.REQUEST_TIMEOUT || code == ErrorCode.CONNECT_TIMEOUT;
        return new OpsError(code, sourceId, getMessage(), retryable, suggestedAction);
    }
}
