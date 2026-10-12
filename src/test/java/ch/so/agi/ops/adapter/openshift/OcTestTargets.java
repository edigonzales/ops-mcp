package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ApplicationDefinition;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.DeploymentDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.SecuritySettings;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.snapshot.InvestigationWindow;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Baut synthetische, aber strukturell vollstaendige Konfigurationen und Ziele fuer die Tests
 * des OpenShift-Adapters. Es werden keine Live-Werte verwendet; {@code api.ocp.so.ch} dient
 * ausschliesslich als Konfigurationsbeispiel einer freigegebenen Clusteradresse.
 */
public final class OcTestTargets {

    /** Feste Testzeit; wird sowohl fuer die Uhr als auch fuer das Budget verwendet. */
    public static final Instant NOW = Instant.parse("2026-10-01T10:05:00Z");

    /** Fensterbeginn fuer den {@code --since-time}-Nachweis. */
    public static final Instant WINDOW_FROM = Instant.parse("2026-10-01T09:56:00Z");

    private OcTestTargets() {
    }

    public static ClusterDefinition cluster(String id, Path kubeconfig, String server, String context,
            List<String> directHosts, List<String> allowedNamespaces) {
        return new ClusterDefinition(id, server, Path.of("/usr/bin/env"), kubeconfig, context,
                SecuritySettings.IDENTITY_RESTRICTED, directHosts, allowedNamespaces);
    }

    /** Clusterdefinition mit dem Fake-oc als Programm. */
    public static ClusterDefinition clusterWithExecutable(String id, Path ocExecutable, Path kubeconfig, String server,
            String context, List<String> directHosts, List<String> allowedNamespaces) {
        return new ClusterDefinition(id, server, ocExecutable, kubeconfig, context,
                SecuritySettings.IDENTITY_RESTRICTED, directHosts, allowedNamespaces);
    }

    public static WorkloadDefinition workload(WorkloadDefinition.WorkloadKind kind, String name,
            List<String> containerNames, boolean allWorkloadContainers) {
        return new WorkloadDefinition(kind, name, containerNames, allWorkloadContainers);
    }

    public static EnvironmentDefinition environment(String clusterId, String namespace, WorkloadDefinition workload) {
        ConfigurationDefinition configuration = new ConfigurationDefinition("deployment-config",
                List.of("fixture/deployment.yaml"), List.of("fixture/params.env"), List.of("PROBE_INITIAL_DELAY"),
                new ConfigurationDefinition.RevisionDefinition(
                        ConfigurationDefinition.RevisionDefinition.Source.JENKINS_SCM,
                        "sogis/openshift-templates", null, null));
        return new EnvironmentDefinition(clusterId, namespace, workload,
                new DeploymentDefinition("apps-deploy", List.of("fixture", "modelfinder")), configuration);
    }

    public static OpsConfig config(ClusterDefinition cluster) {
        return config(cluster, Limits.defaults());
    }

    public static OpsConfig config(ClusterDefinition cluster, Limits limits) {
        return config(cluster, limits, environment(cluster.id(), OcFixtures.NAMESPACE,
                workload(WorkloadDefinition.WorkloadKind.DEPLOYMENT, OcFixtures.WORKLOAD,
                        List.of(OcFixtures.CONTAINER_MAIN), false)));
    }

    public static OpsConfig config(ClusterDefinition cluster, Limits limits, EnvironmentDefinition environment) {
        ApplicationDefinition application = new ApplicationDefinition("fixture-modelfinder", "Fixture Modelfinder",
                Map.of("integration", environment));
        return new OpsConfig(1, DataMode.LIVE, Path.of("fixture-config.yaml"), Path.of("build/fixture-state"),
                new SecuritySettings(false, SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY,
                        SecuritySettings.IDENTITY_RESTRICTED),
                limits, Map.of(cluster.id(), cluster), Map.of(), Map.of(), Map.of("fixture-modelfinder", application),
                null);
    }

    public static CollectionBudget budget(int maxRequests, int maxPods, int maxEvents) {
        return new CollectionBudget(Duration.ofSeconds(90), NOW, maxRequests, maxPods, maxEvents,
                32L * 1024 * 1024, 32 * 1024);
    }

    public static ResolvedTarget target(OpsConfig config, EnvironmentDefinition environment, CollectionBudget budget) {
        return new ResolvedTarget("TGT-fixture0001", config, DataMode.LIVE, "fixture-modelfinder", "integration",
                environment, new BuildRef("apps-deploy", List.of("fixture", "modelfinder"), 42), null, NOW,
                NOW.plus(Duration.ofMinutes(30)), budget, List.of(), null);
    }

    /** Ziel mit der Umgebungsdefinition aus der Konfiguration. */
    public static ResolvedTarget target(OpsConfig config, CollectionBudget budget) {
        EnvironmentDefinition environment = config.application("fixture-modelfinder")
                .environment("integration");
        return target(config, environment, budget);
    }

    /** Fensterlieferant mit bekannter Buildzeit. */
    public static java.util.function.Function<ResolvedTarget, InvestigationWindow> knownWindow() {
        return target -> new InvestigationWindow(WINDOW_FROM, Instant.parse("2026-10-01T10:02:00Z"),
                InvestigationWindow.TimeBasis.BUILD_START_AND_END, "synthetisches Fenster");
    }

    /** Fensterlieferant ohne bekannte Buildzeit. */
    public static java.util.function.Function<ResolvedTarget, InvestigationWindow> unknownWindow() {
        return target -> null;
    }
}
