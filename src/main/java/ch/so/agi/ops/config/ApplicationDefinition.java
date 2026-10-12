package ch.so.agi.ops.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Anwendungsdefinition. Modelfinder ist Konfiguration und Testfall, kein Sonderfall im Code.
 *
 * @param id           Anwendungskennung, z. B. {@code modelfinder}
 * @param displayName  Anzeigename
 * @param environments Umgebungen nach Kennung
 */
public record ApplicationDefinition(String id, String displayName, Map<String, EnvironmentDefinition> environments) {

    public ApplicationDefinition {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        environments = environments == null ? Map.of() : Map.copyOf(environments);
    }

    public EnvironmentDefinition environment(String environment) {
        return environments.get(environment);
    }

    public List<String> environmentNames() {
        return environments.keySet().stream().sorted().toList();
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("applicationId", id);
        map.put("displayName", displayName);
        map.put("environments", environmentNames());
        return map;
    }
}
