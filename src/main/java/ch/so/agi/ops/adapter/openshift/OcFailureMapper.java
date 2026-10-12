package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.util.ExecResult;

import java.util.Locale;

/**
 * Uebersetzt Exitcode und Fehlertext eines {@code oc}-Aufrufs in unterscheidbare Fehlercodes
 * des Fehlervertrags.
 *
 * <p>Ein Fehler ist niemals ein leeres, erfolgreich gelesenes Log. Meldungen werden erneut
 * redigiert und gekuerzt; es werden keine Zugangsdaten, keine vollstaendige Befehlszeile und
 * kein unbegrenzter Fehlerdump uebernommen.</p>
 */
public final class OcFailureMapper {

    /** Kennung eines Prozesses, der gar nicht gestartet werden konnte. */
    public static final String START_FAILURE_MARKER = "prozess konnte nicht gestartet werden";

    private static final int MAX_MESSAGE_LENGTH = 300;

    private OcFailureMapper() {
    }

    /**
     * Ordnet einem fehlgeschlagenen Aufruf einen Fehlercode zu.
     *
     * @param result            Ergebnis des Aufrufs
     * @param previousLogRequest true, wenn {@code oc logs --previous} angefordert wurde
     */
    public static ErrorCode classify(ExecResult result, boolean previousLogRequest) {
        if (result.timedOut()) {
            return ErrorCode.REQUEST_TIMEOUT;
        }
        String text = combinedText(result);
        if (text.contains(START_FAILURE_MARKER)) {
            return ErrorCode.CONFIG_INCOMPLETE;
        }
        if (previousLogRequest && mentionsMissingPreviousContainer(text)) {
            return ErrorCode.NO_PREVIOUS_LOG;
        }
        if (text.contains("x509") || text.contains("certificate") || text.contains("tls:")) {
            return ErrorCode.TLS_UNTRUSTED_CA;
        }
        if (text.contains("proxy authentication")) {
            return ErrorCode.PROXY_AUTH_REQUIRED;
        }
        if (text.contains("forbidden")) {
            return ErrorCode.FORBIDDEN;
        }
        if (text.contains("unauthorized") || text.contains("you must be logged in")) {
            return ErrorCode.AUTH_REQUIRED;
        }
        if (text.contains("no such host") || text.contains("unable to connect to the server")
                || text.contains("server misbehaving") || text.contains("connection refused")
                || text.contains("no route to host") || text.contains("network is unreachable")) {
            return ErrorCode.DNS_FAILURE;
        }
        if (text.contains("doesn't have a resource type") || text.contains("does not have a resource type")
                || text.contains("no matches for kind") || text.contains("could not find the requested resource")) {
            return ErrorCode.UNSUPPORTED_WORKLOAD;
        }
        if (text.contains("i/o timeout") || text.contains("context deadline exceeded")
                || text.contains("dial tcp") && text.contains("timeout") || text.contains("timed out")) {
            return ErrorCode.CONNECT_TIMEOUT;
        }
        if (text.contains("not found") || text.contains("notfound")) {
            return ErrorCode.NOT_FOUND;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    /** Redigierte, einzeilige und gekuerzte Fehlermeldung ohne Zugangsdaten. */
    public static String message(ExecResult result) {
        if (result.timedOut()) {
            return "Der oc-Aufruf wurde nach dem Zeitbudget beendet.";
        }
        String raw = result.redactedStderr();
        if (raw == null || raw.isBlank()) {
            raw = result.stdout();
        }
        if (raw == null || raw.isBlank()) {
            return "oc endete mit Exitcode " + result.exitCode() + " ohne Fehlertext.";
        }
        String single = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        String redacted = Redactor.redact(single).text();
        return redacted.length() > MAX_MESSAGE_LENGTH
                ? redacted.substring(0, MAX_MESSAGE_LENGTH) + "..."
                : redacted;
    }

    /** Manueller naechster Schritt; ohne Zugangsdaten und ohne automatische Reparatur. */
    public static String suggestedAction(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN, AUTH_REQUIRED -> "Die Leseberechtigung der technischen Identitaet fuer "
                    + "Namespace und Workload manuell pruefen.";
            case SCOPE_DENIED -> "Nur konfigurierte und freigegebene Ziele verwenden.";
            case TLS_UNTRUSTED_CA -> "Die zustaendige CA fuer diese API-Adresse manuell pruefen und in der "
                    + "freigegebenen kubeconfig hinterlegen; keine TLS-Pruefung abschalten.";
            case DNS_FAILURE, CONNECT_TIMEOUT, REQUEST_TIMEOUT, PROXY_AUTH_REQUIRED ->
                    "Netzwerk, Proxy-Ausnahme und API-Erreichbarkeit manuell pruefen.";
            case NOT_FOUND -> "Pruefen, ob das Objekt geloescht oder fuer diese Identitaet nicht sichtbar ist; "
                    + "404 ist kein sicherer Existenzbeweis.";
            case NO_PREVIOUS_LOG -> "Vorherige Logs sind nicht verfuegbar; der geloeschte Container wird dadurch "
                    + "nicht wiederhergestellt.";
            case UNSUPPORTED_WORKLOAD -> "Die Workload-Art in der lokalen Konfiguration an den Cluster anpassen.";
            case CONFIG_INCOMPLETE, CONFIG_INVALID -> "Die lokale Konfiguration bzw. die freigegebene kubeconfig "
                    + "vervollstaendigen.";
            case BUDGET_EXCEEDED -> "Das Erfassungsbudget oder den Umfang der Erfassung anpassen.";
            default -> "Den Fehler manuell pruefen; es wird keine automatische Korrektur ausgefuehrt.";
        };
    }

    /** Transport-, TLS- und Authfehler, die auf eine insgesamt nicht erreichbare Quelle deuten. */
    public static boolean isTransportFailure(ErrorCode code) {
        return code == ErrorCode.DNS_FAILURE || code == ErrorCode.CONNECT_TIMEOUT
                || code == ErrorCode.REQUEST_TIMEOUT || code == ErrorCode.TLS_UNTRUSTED_CA
                || code == ErrorCode.PROXY_AUTH_REQUIRED;
    }

    /** Fehler, bei denen die Quelle vorhanden, aber nicht erlaubt ist. */
    public static boolean isDenial(ErrorCode code) {
        return code == ErrorCode.FORBIDDEN || code == ErrorCode.AUTH_REQUIRED || code == ErrorCode.SCOPE_DENIED;
    }

    private static String combinedText(ExecResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr();
        String stdout = result.stdout() == null ? "" : result.stdout();
        return (stderr + "\n" + stdout).toLowerCase(Locale.ROOT);
    }

    private static boolean mentionsMissingPreviousContainer(String text) {
        return text.contains("previous terminated container") || text.contains("previous log")
                || text.contains("unable to retrieve container logs for") && text.contains("previous");
    }
}
