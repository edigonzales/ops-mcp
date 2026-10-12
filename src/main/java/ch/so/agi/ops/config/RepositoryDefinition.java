package ch.so.agi.ops.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repositorydefinition. V1 liest {@code sogis/openshift-templates} ueber einen vom Menschen
 * vorbereiteten lokalen Checkout. Kein automatischer Clone, Fetch oder Pull.
 *
 * @param id            Kennung aus der Konfiguration
 * @param repositoryId  Repositorykennung, z. B. {@code sogis/openshift-templates}
 * @param localPath     lokaler Checkout
 * @param allowedFiles  obere Grenze erlaubter relativer Dateipfade
 */
public record RepositoryDefinition(
        String id,
        String repositoryId,
        Path localPath,
        List<String> allowedFiles) {

    public RepositoryDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repositoryId, "repositoryId");
        allowedFiles = allowedFiles == null ? List.of() : List.copyOf(allowedFiles);
    }

    public boolean isComplete() {
        return localPath != null && !allowedFiles.isEmpty();
    }

    public boolean fileAllowed(String relativePath) {
        return allowedFiles.contains(relativePath);
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("repositoryId", repositoryId);
        map.put("localPathConfigured", localPath != null);
        map.put("allowedFiles", allowedFiles);
        map.put("complete", isComplete());
        return map;
    }
}
