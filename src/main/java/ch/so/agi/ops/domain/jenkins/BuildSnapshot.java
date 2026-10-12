package ch.so.agi.ops.domain.jenkins;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jenkins-Buildmetadaten aus der JSON-Remote-API. Es werden keine Blue-Ocean-, Pipeline-REST-
 * oder Timestamper-Plugins vorausgesetzt; optionale Felder verbessern den Bericht, blockieren
 * aber nie den Minimalzugriff.
 *
 * @param ref                  Buildreferenz (Instanz, Jobpfad, konkrete Nummer)
 * @param displayName          Anzeigename des Builds, soweit vorhanden
 * @param building             true, wenn der Build noch laeuft
 * @param result               SUCCESS, FAILURE, UNSTABLE, ABORTED oder {@code null} bei laufendem Build
 * @param startedAt            Startzeit
 * @param durationMillis       Dauer in Millisekunden; bei laufendem Build ohne Endzeitbeleg
 * @param estimatedDurationMillis geschaetzte Dauer, soweit vorhanden
 * @param timestampAvailable   true, wenn die API einen Zeitstempel geliefert hat
 * @param parameters           nur explizit erlaubte Parameter
 * @param parameterNamesSeen   Namen aller gesehenen Parameter (ohne Werte), fuer Diagnose der Allowlist
 * @param scmRepositories      SCM-Checkouts, soweit strukturiert verfuegbar; mehrere Repositories bleiben getrennt
 * @param imageReferences      Image-Referenzen aus erlaubten Parameterwerten oder SCM-Daten
 * @param targetNamespaceHint  Zielnamespace aus erlaubten Parameterwerten, falls vorhanden
 * @param metadataAvailable    false, wenn nur Konsolendaten vorliegen; dann bleibt der Bericht begrenzt
 * @param notes                Hinweise, insbesondere zu fehlenden optionalen Feldern
 */
public record BuildSnapshot(
        BuildRef ref,
        String displayName,
        Boolean building,
        String result,
        Instant startedAt,
        Long durationMillis,
        Long estimatedDurationMillis,
        boolean timestampAvailable,
        List<BuildParameter> parameters,
        List<String> parameterNamesSeen,
        List<RepositoryDescriptor> scmRepositories,
        List<String> imageReferences,
        String targetNamespaceHint,
        boolean metadataAvailable,
        List<String> notes) {

    public BuildSnapshot {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        parameterNamesSeen = parameterNamesSeen == null ? List.of() : List.copyOf(parameterNamesSeen);
        scmRepositories = scmRepositories == null ? List.of() : List.copyOf(scmRepositories);
        imageReferences = imageReferences == null ? List.of() : List.copyOf(imageReferences);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean isFinished() {
        return "false".equalsIgnoreCase(String.valueOf(building)) || result != null;
    }

    public boolean isFailed() {
        return "FAILURE".equalsIgnoreCase(result) || "UNSTABLE".equalsIgnoreCase(result);
    }

    public boolean isAborted() {
        return "ABORTED".equalsIgnoreCase(result);
    }

    /** Ende nur, wenn der Build abgeschlossen ist und Startzeit plus Dauer belegt sind. */
    public Instant finishedAt() {
        if (startedAt == null || durationMillis == null || !isFinished()) {
            return null;
        }
        return startedAt.plusMillis(durationMillis);
    }

    public Map<String, BuildParameter> parameterMap() {
        return BuildParameter.byName(parameters);
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("instanceId", ref.instanceId());
        map.put("jobPath", ref.jobPath());
        map.put("buildNumber", ref.buildNumber());
        map.put("building", building);
        map.put("result", result);
        map.put("startedAt", startedAt == null ? null : startedAt.toString());
        map.put("durationMillis", durationMillis);
        map.put("timestampAvailable", timestampAvailable);
        map.put("metadataAvailable", metadataAvailable);
        map.put("parameterNamesSeen", parameterNamesSeen);
        map.put("scmRepositories", scmRepositories.size());
        return map;
    }
}
