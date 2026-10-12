package ch.so.agi.ops.domain.jenkins;

import java.util.List;

/**
 * Jenkins-Buildreferenz. Der Jobpfad ist eine Liste von Pfadsegmenten, nie eine
 * ungepruefte vollstaendige URL aus einer Modelleingabe.
 *
 * @param instanceId Jenkins-Kennung aus der Konfiguration (in V1 {@code apps-deploy})
 * @param jobPath    Pfadsegmente des Folder-Jobs, z. B. {@code ["folder", "application"]}
 * @param buildNumber konkrete Buildnummer; bei {@code lastFailed} nach der Aufloesung gesetzt
 */
public record BuildRef(String instanceId, List<String> jobPath, int buildNumber) {

    public BuildRef {
        instanceId = java.util.Objects.requireNonNull(instanceId, "instanceId");
        jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
        if (buildNumber <= 0) {
            throw new IllegalArgumentException("Buildnummer muss positiv sein: " + buildNumber);
        }
    }

    public String jobPathString() {
        return String.join("/", jobPath);
    }

    public String display() {
        return instanceId + ":" + jobPathString() + "#" + buildNumber;
    }
}
