package ch.so.agi.ops.util;

/** Fehler beim Ausfuehren eines freigegebenen externen Programms. */
public class ProcessExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExecResult result;

    public ProcessExecutionException(String message, ExecResult result) {
        super(message);
        this.result = result;
    }

    public ExecResult result() {
        return result;
    }
}
