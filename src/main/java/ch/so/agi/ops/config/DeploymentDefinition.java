package ch.so.agi.ops.config;

import java.util.List;

/**
 * Jenkins-Zuordnung einer Umgebung.
 *
 * @param jenkins Jenkins-Kennung
 * @param jobPath Folder-Jobpfad als Liste von Segmenten, z. B. {@code ['folder', 'application']}
 */
public record DeploymentDefinition(String jenkins, List<String> jobPath) {

    public DeploymentDefinition {
        jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
    }
}
