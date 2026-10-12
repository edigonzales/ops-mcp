package ch.so.agi.ops.domain.k8s;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Containerstatus. Init-Container und Sidecars werden getrennt gefuehrt, damit sie nicht
 * als Hauptcontainerfehler verwechselt werden.
 *
 * @param name             Containername
 * @param containerClass   MAIN, INIT oder SIDECAR
 * @param image            Image-Referenz
 * @param imageId          Image-ID/Digest, soweit verfuegbar
 * @param ready            ready-Flag
 * @param restartCount     Restartcount
 * @param state            aktueller Zustand
 * @param lastState        letzter Zustand (lastState), typischerweise Terminated nach Neustart
 * @param started          started-Flag, soweit vorhanden
 * @param startedAt        Startzeit des aktuellen Containerprozesses, falls im Zustand enthalten
 * @param finishedAt       Endzeit des letzten Containerprozesses, falls im Zustand enthalten
 */
public record ContainerSnapshot(
        String name,
        ContainerClass containerClass,
        String image,
        String imageId,
        Boolean ready,
        Integer restartCount,
        ContainerState state,
        ContainerState lastState,
        Boolean started,
        Instant startedAt,
        Instant finishedAt) {

    public enum ContainerClass { MAIN, INIT, SIDECAR }

    /**
     * Containerzustand als Summe der Kubernetes-Felder. Genau eines der Felder ist typischerweise gesetzt.
     *
     * @param kind       WAITING, RUNNING oder TERMINATED
     * @param reason     Reason aus dem Zustand (z. B. OOMKilled, Error, Completed, CrashLoopBackOff)
     * @param message    Meldung aus dem Zustand, redigiert
     * @param exitCode   Exitcode, falls beendet
     * @param signal     Signal, falls beendet
     * @param startedAt  Startzeit, falls laufend/beendet
     * @param finishedAt Endzeit, falls beendet
     */
    public record ContainerState(
            StateKind kind,
            String reason,
            String message,
            Integer exitCode,
            Integer signal,
            Instant startedAt,
            Instant finishedAt) {

        public enum StateKind { WAITING, RUNNING, TERMINATED }

        public boolean isTerminated() {
            return kind == StateKind.TERMINATED;
        }

        public boolean isWaiting() {
            return kind == StateKind.WAITING;
        }
    }

    /** Abgeleitete, rein mechanische Beobachtungen; keine Kausalaussage. */
    public record ContainerObservations(
            boolean hadRestart,
            boolean lastStateTerminated,
            String lastTerminationReason,
            Integer lastExitCode,
            List<String> flags) {

        public ContainerObservations {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }

        public static ContainerObservations none() {
            return new ContainerObservations(false, false, null, null, List.of());
        }
    }

    /** Aus den Containerdaten mechanisch abgeleitete Hinweise; bewusst ohne Ursachenbehauptung. */
    public ContainerObservations observations() {
        List<String> flags = new java.util.ArrayList<>();
        boolean hadRestart = restartCount != null && restartCount > 0;
        boolean lastTerminated = lastState != null && lastState.isTerminated();
        String lastReason = lastTerminated ? lastState.reason() : null;
        Integer lastExit = lastTerminated ? lastState.exitCode() : null;
        if (hadRestart) {
            flags.add("restart-count-groesser-null");
        }
        if (lastTerminated) {
            flags.add("last-state-terminated");
        }
        if (lastTerminated && "OOMKilled".equalsIgnoreCase(lastReason)) {
            flags.add("oomkilled-belegt");
        } else if (lastTerminated && lastExit != null && lastExit == 137) {
            flags.add("exitcode-137-ohne-oom-begruendung");
        }
        if (state != null && state.isWaiting() && state.reason() != null) {
            flags.add("wartet:" + state.reason());
        }
        return new ContainerObservations(hadRestart, lastTerminated, lastReason, lastExit, flags);
    }

    /** Kurzbeschreibung ohne Interpretation, fuer Textdarstellungen. */
    public String describeState() {
        if (state == null) {
            return "unbekannt";
        }
        StringBuilder sb = new StringBuilder(state.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (state.reason() != null) {
            sb.append('/').append(state.reason());
        }
        if (state.exitCode() != null) {
            sb.append(" exit=").append(state.exitCode());
        }
        return sb.toString();
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", name);
        map.put("containerClass", containerClass.name());
        map.put("image", image);
        map.put("ready", ready);
        map.put("restartCount", restartCount);
        map.put("state", describeState());
        map.put("lastStateReason", lastState == null ? null : lastState.reason());
        map.put("lastExitCode", lastState == null ? null : lastState.exitCode());
        return map;
    }

    public static ContainerSnapshot main(String name) {
        return new ContainerSnapshot(name, ContainerClass.MAIN, null, null, null, null, null, null, null, null, null);
    }

    public static Map<String, ContainerSnapshot> byName(List<ContainerSnapshot> list) {
        Map<String, ContainerSnapshot> map = new java.util.LinkedHashMap<>();
        for (ContainerSnapshot c : list) {
            map.put(c.name(), c);
        }
        return map;
    }
}
