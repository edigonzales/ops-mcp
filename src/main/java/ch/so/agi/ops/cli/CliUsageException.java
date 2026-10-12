package ch.so.agi.ops.cli;

/** Ungueltige Kommandozeilenverwendung. Fuehrt zu Exitcode 2. */
public class CliUsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliUsageException(String message) {
        super(message);
    }
}
