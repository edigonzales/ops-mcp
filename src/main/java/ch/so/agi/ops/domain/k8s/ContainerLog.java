package ch.so.agi.ops.domain.k8s;

import java.util.List;
import java.util.Objects;

/**
 * Erfasstes Container-Log. Aktuelle Logs und, bei passendem Neustart, vorherige Logs
 * ({@code --previous}) werden pro explizitem Container getrennt gefuehrt.
 *
 * <p>Ein Fehler wie "vorheriger Container existiert nicht", {@code Forbidden}, ein TLS-Fehler
 * oder ein API-Timeout ist <em>kein</em> leeres, erfolgreich gelesenes Log. Ein geloeschter Pod
 * wird durch {@code --previous} nicht wiederhergestellt.</p>
 *
 * @param podName        Podname
 * @param podUid         Pod-UID
 * @param containerName  Containername
 * @param previous       true fuer {@code --previous}
 * @param available      true, wenn Logs tatsaechlich gelesen wurden
 * @param lines          bereinigte Logzeilen
 * @param truncated      true, wenn das Bytebudget vor dem Ende erreicht wurde
 * @param truncationNote Klartextnotiz zur Truncation
 * @param skipReason     Grund, warum nicht gelesen wurde (z. B. Restartcount 0)
 * @param errorCode      Fehlercode, falls das Lesen fehlschlug
 * @param errorMessage   redigierte Fehlermeldung
 * @param sinceTime      verwendeter sinceTime-Parameter, soweit gesetzt
 */
public record ContainerLog(
        String podName,
        String podUid,
        String containerName,
        boolean previous,
        boolean available,
        List<String> lines,
        boolean truncated,
        String truncationNote,
        String skipReason,
        String errorCode,
        String errorMessage,
        java.time.Instant sinceTime) {

    public ContainerLog {
        Objects.requireNonNull(containerName, "containerName");
        lines = lines == null ? List.of() : List.copyOf(lines);
        truncationNote = truncationNote == null ? "" : truncationNote;
        skipReason = skipReason == null ? "" : skipReason;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public String key() {
        return podName + "/" + containerName + (previous ? "(previous)" : "");
    }

    public static ContainerLog skipped(String podName, String podUid, String containerName, boolean previous,
            String reason) {
        return new ContainerLog(podName, podUid, containerName, previous, false, List.of(), false, "", reason, null,
                "", null);
    }

    public static ContainerLog failed(String podName, String podUid, String containerName, boolean previous,
            String errorCode, String errorMessage) {
        return new ContainerLog(podName, podUid, containerName, previous, false, List.of(), false, "", "", errorCode,
                errorMessage, null);
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("pod", podName);
        map.put("container", containerName);
        map.put("previous", previous);
        map.put("available", available);
        map.put("lineCount", lines.size());
        map.put("truncated", truncated);
        if (!skipReason.isBlank()) {
            map.put("skipReason", skipReason);
        }
        if (errorCode != null) {
            map.put("errorCode", errorCode);
        }
        return map;
    }
}
