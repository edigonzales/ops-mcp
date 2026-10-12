package ch.so.agi.ops.domain;

/**
 * Stabiler Fehlercodevertrag aus SCHNITTSTELLEN.md Abschnitt 7.
 * Diese Werte erscheinen in Toolausgaben und Snapshots wieder.
 */
public enum ErrorCode {
    CONFIG_INVALID,
    CONFIG_INCOMPLETE,
    TARGET_AMBIGUOUS,
    TARGET_EXPIRED,
    SCOPE_DENIED,
    DNS_FAILURE,
    CONNECT_TIMEOUT,
    REQUEST_TIMEOUT,
    PROXY_AUTH_REQUIRED,
    TLS_UNTRUSTED_CA,
    AUTH_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    UNSUPPORTED_WORKLOAD,
    NO_PREVIOUS_LOG,
    REVISION_UNKNOWN,
    REVISION_UNVERIFIED,
    REVISION_UNAVAILABLE,
    DATA_TRUNCATED,
    BUDGET_EXCEEDED,
    SOURCE_PARSE_ERROR,
    REPORT_INVALID,
    /** Zusaetzlich: ein lokales Artefakt (Snapshot/Evidence/Report) ist beschaedigt oder manipuliert. */
    ARTIFACT_INTEGRITY,
    /** Zusaetzlich: Eingabe hat ein unzulaessiges Format (CLI-/Toolargumente). */
    INVALID_INPUT,
    /** Zusaetzlich: ein einzelnes Argument ueberschreitet die zulaessige Laenge. */
    INPUT_TOO_LONG,
    /** Zusaetzlich: interner, nicht erwarteter Fehler; Details bleiben redigiert. */
    INTERNAL_ERROR
}
