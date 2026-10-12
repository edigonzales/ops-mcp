package ch.so.agi.ops.config;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

import java.util.List;

/**
 * Fehler in der lokalen Konfiguration. Traegt den stabilen Fehlercode und alle gefundenen
 * Einzelprobleme, damit {@code doctor} und die CLI eine verstaendliche Liste ausgeben koennen.
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<OpsError> problems;

    public ConfigException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ConfigException(ErrorCode code, String message, List<OpsError> problems) {
        super(message);
        this.code = code;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public ErrorCode code() {
        return code;
    }

    public List<OpsError> problems() {
        return problems;
    }

    public static ConfigException invalid(String message) {
        return new ConfigException(ErrorCode.CONFIG_INVALID, message);
    }

    public static ConfigException incomplete(String message) {
        return new ConfigException(ErrorCode.CONFIG_INCOMPLETE, message);
    }
}
