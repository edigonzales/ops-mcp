package ch.so.agi.ops.domain.jenkins;

import java.util.List;

/**
 * Auswahl des zu untersuchenden Builds.
 *
 * @param kind        NUMBER, LAST_FAILED oder LAST_UNSUCCESSFUL
 * @param number      konkrete Nummer bei {@code NUMBER}, sonst {@code null}
 * @param jobPath     Jobpfad; bei {@code LAST_FAILED} aus der Konfiguration, bei {@code NUMBER} optional aus der Konfiguration
 * @param buildUrl    optionaler Browserlink, der gegen die Allowlist aufgeloest wurde
 */
public record BuildSelector(Kind kind, Integer number, List<String> jobPath, String buildUrl) {

    public enum Kind { NUMBER, LAST_FAILED, LAST_UNSUCCESSFUL }

    public BuildSelector {
        jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
    }

    public static BuildSelector ofNumber(int number) {
        return new BuildSelector(Kind.NUMBER, number, List.of(), null);
    }

    public static BuildSelector lastFailed() {
        return new BuildSelector(Kind.LAST_FAILED, null, List.of(), null);
    }

    /** {@code lastFailed} ist nicht {@code lastUnsuccessful}: abgebrochene Builds sind keine Fehler. */
    public boolean isLastFailed() {
        return kind == Kind.LAST_FAILED;
    }
}
