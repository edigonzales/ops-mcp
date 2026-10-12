package ch.so.agi.ops.domain;

import java.util.Objects;

/**
 * Strukturierter, redigierter Fehler. Es werden niemals Zugangsdaten, Tokenwerte,
 * kubeconfig-Inhalte oder vollstaendige Umgebungen in {@code message} oder
 * {@code suggestedAction} uebernommen.
 *
 * @param code            stabiler Fehlercode
 * @param sourceId        Kennung der betroffenen Quelle (Cluster, Jenkins-Instanz, Repository, Zielpfad)
 * @param message         redigierte, fachliche Erklaerung
 * @param retryable       ob ein begrenzter Retry innerhalb desselben Budgets zulaessig ist
 * @param suggestedAction konkreter manueller naechster Schritt fuer den Betreiber
 */
public record OpsError(
        ErrorCode code,
        String sourceId,
        String message,
        boolean retryable,
        String suggestedAction) {

    public OpsError {
        Objects.requireNonNull(code, "code");
        sourceId = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId;
        message = message == null || message.isBlank() ? code.name() : message;
        suggestedAction = suggestedAction == null ? "" : suggestedAction;
    }

    public static OpsError of(ErrorCode code, String sourceId, String message) {
        return new OpsError(code, sourceId, message, false, "");
    }

    /** Quelle ist nur eine Kennung; zum Beispiel ein Optionsname oder ein Anwendungsbezeichner. */
    public OpsError withSource(String newSourceId) {
        return new OpsError(code, newSourceId, message, retryable, suggestedAction);
    }
}
