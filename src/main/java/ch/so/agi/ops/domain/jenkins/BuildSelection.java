package ch.so.agi.ops.domain.jenkins;

/**
 * Ergebnis der Buildaufloesung. Enthaelt die konkrete Nummer und - falls bekannt - eine
 * Begruendung, warum genau dieser Build gewaehlt wurde.
 *
 * @param instanceId Jenkins-Kennung
 * @param jobPath    Jobpfadsegmente
 * @param buildNumber aufgeloeste, konkrete Nummer
 * @param result     Ergebnis des Builds, soweit bekannt
 * @param building   true, wenn der Build noch laeuft
 * @param reason     Klartextbegruendung der Auswahl
 * @param queriedLastFailed true, wenn dafuer ein gezielter Aufruf noetig war
 */
public record BuildSelection(
        String instanceId,
        java.util.List<String> jobPath,
        int buildNumber,
        String result,
        Boolean building,
        String reason,
        boolean queriedLastFailed) {

    public BuildSelection {
        jobPath = jobPath == null ? java.util.List.of() : java.util.List.copyOf(jobPath);
        reason = reason == null ? "" : reason;
    }

    public BuildRef toBuildRef() {
        return new BuildRef(instanceId, jobPath, buildNumber);
    }
}
