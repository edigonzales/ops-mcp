package ch.so.agi.ops.application.port;

import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelector;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Vollstaendig aufgeloestes Untersuchungsziel. Es wird serverseitig aus der Konfiguration
 * gebildet; ein Modell kann Scope, Namespace oder Build nicht nachtraeglich veraendern.
 *
 * @param targetId       serverseitig erzeugte, undurchsichtige Kennung
 * @param config         verwendete Konfiguration
 * @param dataMode       Betriebsmodus
 * @param applicationId  bestaetigte Anwendung
 * @param environment    bestaetigte Umgebung
 * @param definition     Umgebungsdefinition aus der Konfiguration
 * @param buildRef       konkrete Buildreferenz
 * @param credential     bestaetigte Zuordnung der Credentialquelle, falls gesetzt
 * @param resolvedAt     Aufloesungszeit
 * @param expiresAt      Ablaufzeit des Handles
 * @param budget         auftragsweites Erfassungsbudget
 * @param warnings       Hinweise aus der Aufloesung
 * @param scenarioId     Fixture-Szenario, nur im Modus {@code fixture}
 */
public record ResolvedTarget(
        String targetId,
        OpsConfig config,
        DataMode dataMode,
        String applicationId,
        String environment,
        EnvironmentDefinition definition,
        BuildRef buildRef,
        String credential,
        Instant resolvedAt,
        Instant expiresAt,
        CollectionBudget budget,
        List<String> warnings,
        String scenarioId) {

    public ResolvedTarget {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataMode, "dataMode");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(buildRef, "buildRef");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String clusterId() {
        return definition.cluster();
    }

    public String namespace() {
        return definition.namespace();
    }

    public boolean isFixture() {
        return dataMode == DataMode.FIXTURE;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public String jobPathString() {
        return String.join("/", buildRef.jobPath());
    }

    public List<String> selectedContainerNames() {
        return definition.workload() == null ? List.of() : definition.workload().containerNames();
    }

    /** Zielkennung fuer Links im Snapshot, z. B. {@code workload:ocp/ns/Deployment/name}. */
    public String workloadNodeId() {
        if (definition.workload() == null) {
            return "workload:" + clusterId() + "/" + namespace() + "/unknown";
        }
        return "workload:" + clusterId() + "/" + namespace() + "/"
                + definition.workload().kind().kubernetesKind() + "/" + definition.workload().name();
    }

    public String buildNodeId() {
        return "build:" + buildRef.display();
    }

    public String configurationNodeId() {
        if (definition.configuration() == null) {
            return "configuration:unbekannt";
        }
        return "configuration:" + definition.configuration().repository() + "@" + buildRef.buildNumber();
    }

    public static BuildSelector selectorOf(BuildRef ref) {
        return BuildSelector.ofNumber(ref.buildNumber());
    }
}
