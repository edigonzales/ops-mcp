package ch.so.agi.ops.application;

import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.application.port.ResolvedTarget;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Baut aus Anwendung, Umgebung und Build ein aufgeloestes Untersuchungsziel.
 *
 * <p>Ohne eindeutige Zuordnung wird keine Umgebung, kein Workload und kein Build geraten.
 * Ein unvollstaendiger Konfigurationseintrag blockiert nur diesen Auftrag, nicht den
 * Fixturebetrieb.</p>
 */
public final class TargetBuilder {

    /** Gueltigkeitsdauer eines Target-Handles im Serverprozess. */
    public static final Duration HANDLE_TTL = Duration.ofMinutes(30);

    private final OpsConfig config;
    private final ClockProvider clock;

    public TargetBuilder(OpsConfig config, ClockProvider clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Erzeugt ein Ziel.
     *
     * @param applicationId Anwendungskennung
     * @param environment   Umgebungsname
     * @param buildRef      konkrete Buildreferenz
     * @param scenarioId    Fixture-Szenario oder {@code null}
     * @param warnings      zusaetzliche Hinweise
     */
    public ResolvedTarget build(String applicationId, String environment,
            ch.so.agi.ops.domain.jenkins.BuildRef buildRef, String scenarioId, List<String> warnings) {
        if (config.storageRoot() == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "storage.root ist nicht konfiguriert; ohne lokalen Ausgabebereich kann kein Snapshot "
                            + "gespeichert werden.");
        }
        if (applicationId == null || applicationId.isBlank()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Es wurde keine Anwendung angegeben. Bekannt sind: " + String.join(", ", config.knownApplicationIds()));
        }
        var application = config.application(applicationId);
        if (application == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Unbekannte Anwendung '" + sanitize(applicationId) + "'. Bekannt sind: "
                            + String.join(", ", config.knownApplicationIds()));
        }
        if (environment == null || environment.isBlank()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Es wurde keine Umgebung angegeben. Bekannt sind: "
                            + String.join(", ", application.environmentNames()));
        }
        EnvironmentDefinition definition = application.environment(environment);
        if (definition == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Unbekannte Umgebung '" + sanitize(environment) + "' fuer Anwendung '"
                            + sanitize(applicationId) + "'. Bekannt sind: "
                            + String.join(", ", application.environmentNames()));
        }
        List<String> missing = definition.missingFields();
        if (!missing.isEmpty()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Konfigurationseintrag " + applicationId + "/" + environment
                            + " ist unvollstaendig. Fehlend: " + String.join(", ", missing)
                            + ". Der Eintrag muss vom Betreiber vervollstaendigt werden; es wird nichts geraten.",
                    new OpsError(ErrorCode.CONFIG_INCOMPLETE, applicationId + "/" + environment,
                            "Fehlende Pflichtangaben: " + String.join(", ", missing), false,
                            "Die lokale Konfiguration vervollstaendigen."));
        }
        var cluster = config.cluster(definition.cluster());
        if (cluster == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INVALID,
                    "Unbekannte Clusterkennung: " + sanitize(definition.cluster()));
        }
        if (config.isLive() && !cluster.isComplete()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Cluster '" + sanitize(cluster.id()) + "' ist im Livemodus unvollstaendig "
                            + "(server, ocExecutable, kubeconfig, context).");
        }
        var jenkins = config.jenkins(definition.deployment().jenkins());
        if (jenkins == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INVALID,
                    "Unbekannte Jenkins-Kennung: " + sanitize(definition.deployment().jenkins()));
        }
        if (config.isLive() && !jenkins.isComplete()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Die Jenkins-Instanz '" + sanitize(jenkins.id())
                            + "' ist im Livemodus unvollstaendig (baseUrl, token).");
        }
        var repository = config.repository(definition.configuration().repository());
        if (repository == null) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INVALID,
                    "Unbekannte Repositorykennung: " + sanitize(definition.configuration().repository()));
        }
        if (config.isLive() && !repository.isComplete()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Das Repository '" + sanitize(repository.id())
                            + "' ist im Livemodus unvollstaendig (localPath, allowedFiles).");
        }
        if (!cluster.namespaceAllowed(definition.namespace()) && !cluster.allowedNamespaces().isEmpty()) {
            throw new TargetResolutionException(ErrorCode.SCOPE_DENIED,
                    "Der Namespace '" + sanitize(definition.namespace()) + "' ist fuer Cluster '"
                            + sanitize(cluster.id()) + "' nicht freigegeben.");
        }
        if (!jenkins.allowedJobPaths().isEmpty() && !jenkins.jobPathAllowed(definition.deployment().jobPath())) {
            throw new TargetResolutionException(ErrorCode.SCOPE_DENIED,
                    "Der Jobpfad ist fuer Jenkins '" + sanitize(jenkins.id()) + "' nicht freigegeben.");
        }
        checkSecurityMode(cluster);

        Instant now = clock.now();
        CollectionBudget budget = CollectionBudget.of(
                Duration.ofSeconds(config.limits().collectTimeoutSeconds()),
                now,
                config.limits().maxRequests(),
                config.limits().maxPods(),
                config.limits().maxEvents(),
                config.limits().maxTotalInputBytes(),
                config.limits().maxEvidenceResponseBytes());

        return new ResolvedTarget(
                "TGT-" + UUID.randomUUID().toString().substring(0, 12),
                config,
                config.mode(),
                applicationId,
                environment,
                definition,
                buildRef,
                null,
                now,
                now.plus(HANDLE_TTL),
                budget,
                warnings == null ? List.of() : List.copyOf(warnings),
                scenarioId);
    }

    /**
     * Die Betreiberdeklaration {@code identityMode} ist kein automatisch verifizierter
     * Rechtebeweis. Ein persoenlicher Prototypzugang ist nur mit ausdruecklicher Freigabe
     * zulaessig; die Anwendung leitet daraus keine RBAC-Garantie ab.
     */
    private void checkSecurityMode(ch.so.agi.ops.config.ClusterDefinition cluster) {
        if (config.mode() != DataMode.LIVE) {
            return;
        }
        if (cluster.isComplete() && ch.so.agi.ops.config.SecuritySettings.IDENTITY_PERSONAL_PROTOTYPE
                .equals(cluster.identityMode()) && !config.security().allowPersonalCredentials()) {
            throw new TargetResolutionException(ErrorCode.CONFIG_INCOMPLETE,
                    "Cluster '" + sanitize(cluster.id()) + "' ist als personal-prototype deklariert, aber "
                            + "security.allowPersonalCredentials ist nicht gesetzt. Ein persoenlicher "
                            + "Prototypzugang muss ausdruecklich freigegeben werden.");
        }
    }

    /** Zielpfad des lokalen Ausgabebereichs. */
    public Path storageRoot() {
        return config.storageRoot();
    }

    static String sanitize(String value) {
        return ch.so.agi.ops.security.PathSafety.describe(value);
    }
}
