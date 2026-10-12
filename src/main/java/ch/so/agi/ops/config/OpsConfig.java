package ch.so.agi.ops.config;

import ch.so.agi.ops.domain.DataMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validierte lokale Konfiguration. Der Modus ist entweder {@code fixture} oder {@code live}.
 * Alle relativen Pfade sind hier bereits gegen das Verzeichnis der Konfigurationsdatei
 * aufgeloest; {@code ~} und {@code ${HOME}} sind die einzigen unterstuetzten Abkuerzungen.
 *
 * <p>Die Allowed-Listen des Systems sind die obere Berechtigungsgrenze. Anwendungseintraege
 * duerfen sie nur weiter einschraenken.</p>
 *
 * @param schemaVersion Schema-Version; in V1 genau {@code 1}
 * @param mode          Betriebsmodus
 * @param sourceFile    absoluter Pfad der verwendeten Konfigurationsdatei
 * @param storageRoot   lokaler Ausgabebereich
 * @param security      Sicherheitseinstellungen
 * @param limits        Ressourcengrenzen
 * @param clusters      Clusterdefinitionen nach Kennung
 * @param jenkins       Jenkins-Instanzen nach Kennung
 * @param repositories  Repositorydefinitionen nach Kennung
 * @param applications  Anwendungen nach Kennung
 * @param fixture       Fixtureeinstellungen; nur im Modus {@code fixture} zulaessig
 */
public record OpsConfig(
        int schemaVersion,
        DataMode mode,
        Path sourceFile,
        Path storageRoot,
        SecuritySettings security,
        Limits limits,
        Map<String, ClusterDefinition> clusters,
        Map<String, JenkinsDefinition> jenkins,
        Map<String, RepositoryDefinition> repositories,
        Map<String, ApplicationDefinition> applications,
        FixtureSettings fixture) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public OpsConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(sourceFile, "sourceFile");
        // storageRoot darf beim Sammeln von Konfigurationsproblemen null sein: Der Loader
        // meldet dann CONFIG_INCOMPLETE, statt eine irrefuehrende NullPointerException zu
        // erzeugen. Ein betriebsfaehiger Aufruf scheitert ohnehin vorher an der Validierung.
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(limits, "limits");
        clusters = clusters == null ? Map.of() : Map.copyOf(clusters);
        jenkins = jenkins == null ? Map.of() : Map.copyOf(jenkins);
        repositories = repositories == null ? Map.of() : Map.copyOf(repositories);
        applications = applications == null ? Map.of() : Map.copyOf(applications);
    }

    public boolean isFixture() {
        return mode == DataMode.FIXTURE;
    }

    public boolean isLive() {
        return mode == DataMode.LIVE;
    }

    public ApplicationDefinition application(String applicationId) {
        return applications.get(applicationId);
    }

    public ClusterDefinition cluster(String clusterId) {
        return clusters.get(clusterId);
    }

    public JenkinsDefinition jenkins(String instanceId) {
        return jenkins.get(instanceId);
    }

    public RepositoryDefinition repository(String repositoryId) {
        return repositories.get(repositoryId);
    }

    /** Nur fuer strukturierte Ausgaben; enthaelt keine Credentialpfade. */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("mode", mode.wireName());
        map.put("storageRoot", storageRoot.toString());
        map.put("clusters", clusters.values().stream().map(ClusterDefinition::toPublicMap).toList());
        map.put("jenkins", jenkins.values().stream().map(JenkinsDefinition::toPublicMap).toList());
        map.put("repositories", repositories.values().stream().map(RepositoryDefinition::toPublicMap).toList());
        map.put("applications", applications.values().stream().map(ApplicationDefinition::toPublicMap).toList());
        map.put("limits", limits.toMap());
        map.put("security", security.toMap());
        if (fixture != null) {
            map.put("fixture", fixture.toPublicMap());
        }
        return map;
    }

    public List<String> knownApplicationIds() {
        return applications.keySet().stream().sorted().toList();
    }
}
