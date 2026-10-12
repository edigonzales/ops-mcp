package ch.so.agi.ops.domain.k8s;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Normalisierte Probe-Konfiguration eines Containers.
 *
 * <p>Probe-Header-Werte und Exec-Argumente koennen Secrets enthalten und werden deshalb
 * vor der Speicherung redigiert. Ist {@code redacted} gesetzt, bleibt die Diagnose an
 * dieser Stelle bewusst eingeschraenkt; das Feld {@code redactionNote} nennt den Grund.</p>
 *
 * @param containerName    Containername
 * @param kind             LIVENESS, READINESS oder STARTUP
 * @param handlerKind      HTTP_GET, TCP_SOCKET, EXEC oder UNKNOWN
 * @param httpPath         Pfad bei HTTP_GET
 * @param httpPort         Portangabe bei HTTP_GET oder TCP_SOCKET (Zahl oder Portname)
 * @param httpScheme       HTTP oder HTTPS
 * @param httpHeaderNames  Namen der Header (Werte werden nicht uebernommen)
 * @param execCommandRedacted true, wenn Exec-Argumente redigiert wurden
 * @param initialDelaySeconds initialDelaySeconds
 * @param periodSeconds       periodSeconds
 * @param timeoutSeconds      timeoutSeconds
 * @param successThreshold    successThreshold
 * @param failureThreshold    failureThreshold
 * @param terminationGracePeriodSeconds terminationGracePeriodSeconds, falls gesetzt
 * @param redacted         true, wenn mindestens ein sensitiver Wert entfernt wurde
 * @param redactionNote    Klartextnotiz zur Redaktion
 */
public record ProbeConfig(
        String containerName,
        ProbeKind kind,
        HandlerKind handlerKind,
        String httpPath,
        String httpPort,
        String httpScheme,
        List<String> httpHeaderNames,
        boolean execCommandRedacted,
        Integer initialDelaySeconds,
        Integer periodSeconds,
        Integer timeoutSeconds,
        Integer successThreshold,
        Integer failureThreshold,
        Integer terminationGracePeriodSeconds,
        boolean redacted,
        String redactionNote) {

    public enum ProbeKind { LIVENESS, READINESS, STARTUP }

    public enum HandlerKind { HTTP_GET, TCP_SOCKET, EXEC, UNKNOWN }

    public ProbeConfig {
        httpHeaderNames = httpHeaderNames == null ? List.of() : List.copyOf(httpHeaderNames);
        redactionNote = redactionNote == null ? "" : redactionNote;
    }

    /**
     * Vereinfachte Orientierung fuer die Startphase. Ausdruecklich <em>kein</em> exakt
     * berechneter Kill-Zeitpunkt: initialDelaySeconds + failureThreshold x periodSeconds
     * ist hoechstens eine grobe Orientierung (siehe SPEZIFIKATION_V1.md Abschnitt 6.2).
     */
    public Integer simplifiedStartBudgetSeconds() {
        if (initialDelaySeconds == null || failureThreshold == null || periodSeconds == null) {
            return null;
        }
        return initialDelaySeconds + failureThreshold * periodSeconds;
    }

    public static ProbeConfig unknown(String containerName) {
        return new ProbeConfig(containerName, null, HandlerKind.UNKNOWN, null, null, null, List.of(), false,
                null, null, null, null, null, null, false, "");
    }
}
