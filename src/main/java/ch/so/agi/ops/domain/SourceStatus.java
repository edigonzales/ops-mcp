package ch.so.agi.ops.domain;

/**
 * Erfassungsstatus einer einzelnen Quelle. Eine leere Liste ist niemals ein allgemeiner
 * Ersatz fuer eine Exception: Jede Quelle berichtet explizit, warum sie keine Daten liefert.
 */
public enum SourceStatus {
    /** Die geplante begrenzte Erfassung dieser Quelle war technisch erfolgreich. */
    COLLECTED,
    /** Die Quelle ist erreichbar, die erwarteten Daten existieren aber nicht. */
    NOT_PRESENT,
    /** Die Quelle ist fuer diesen Fall fachlich nicht anwendbar (z. B. keine vorherigen Logs bei Restartcount 0). */
    NOT_APPLICABLE,
    /** Die Quelle ist vorhanden, aber durch Konfiguration oder Rechte nicht erlaubt. */
    NOT_ALLOWED,
    /** Die Daten sind nur teilweise erfasst (Budget, abgeschnittene Antwort, partielle Fehler). */
    TRUNCATED,
    /** Die Quelle ist technisch nicht erreichbar (Transport, TLS, Auth, Timeout). */
    UNREACHABLE,
    /** Die Quelle wurde wegen eines uebergeordneten Abbruchs nicht mehr versucht. */
    NOT_ATTEMPTED;

    public boolean isUsable() {
        return this == COLLECTED || this == TRUNCATED || this == NOT_PRESENT || this == NOT_APPLICABLE;
    }

    public boolean isGap() {
        return this == TRUNCATED || this == UNREACHABLE || this == NOT_ALLOWED;
    }
}
