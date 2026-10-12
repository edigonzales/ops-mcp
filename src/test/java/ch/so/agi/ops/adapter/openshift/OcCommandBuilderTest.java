package ch.so.agi.ops.adapter.openshift;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Beweist die exakten Argumentlisten der fest erlaubten, rein lesenden {@code oc}-Aufrufe.
 */
class OcCommandBuilderTest {

    private static final String KUBECONFIG = "/tmp/fixture-kubeconfig";
    private static final String CONTEXT = "fixture-context";
    private static final String NAMESPACE = "fixture-integration";
    private static final String NAME = "fixture-modelfinder";
    private static final String SELECTOR = "app=fixture-modelfinder";

    private final OcCommandBuilder builder = new OcCommandBuilder(KUBECONFIG, CONTEXT);

    @Test
    void versionListeIstExakt() {
        assertEquals(List.of("version", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-o", "json"),
                builder.version());
    }

    @Test
    void whoamiServerListeIstExaktUndLesend() {
        assertEquals(List.of("whoami", "--show-server", "--kubeconfig", KUBECONFIG, "--context", CONTEXT),
                builder.whoamiServer());
    }

    @Test
    void configCurrentContextListeIstExakt() {
        assertEquals(List.of("config", "current-context", "--kubeconfig", KUBECONFIG),
                builder.configCurrentContext());
    }

    @Test
    void getDeploymentListeIstExakt() {
        assertEquals(List.of("get", "deployment", NAME, "--kubeconfig", KUBECONFIG, "--context", CONTEXT,
                "-n", NAMESPACE, "-o", "json"), builder.getDeployment(NAMESPACE, NAME));
    }

    @Test
    void getDeploymentConfigListeIstExakt() {
        assertEquals(List.of("get", "deploymentconfig", NAME, "--kubeconfig", KUBECONFIG, "--context", CONTEXT,
                "-n", NAMESPACE, "-o", "json"), builder.getDeploymentConfig(NAMESPACE, NAME));
    }

    @Test
    void getReplicaSetsListeIstExakt() {
        assertEquals(List.of("get", "replicasets", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n",
                NAMESPACE, "-l", SELECTOR, "-o", "json"), builder.getReplicaSets(NAMESPACE, SELECTOR));
    }

    @Test
    void getReplicationControllersListeIstExakt() {
        assertEquals(List.of("get", "replicationcontrollers", "--kubeconfig", KUBECONFIG, "--context", CONTEXT,
                "-n", NAMESPACE, "-l", SELECTOR, "-o", "json"),
                builder.getReplicationControllers(NAMESPACE, SELECTOR));
    }

    @Test
    void getPodsListeIstExakt() {
        assertEquals(List.of("get", "pods", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                "-l", SELECTOR, "-o", "json"), builder.getPods(NAMESPACE, SELECTOR));
    }

    @Test
    void getPodListeIstExakt() {
        assertEquals(List.of("get", "pods", OcFixtures.POD_A, "--kubeconfig", KUBECONFIG, "--context", CONTEXT,
                "-n", NAMESPACE, "-o", "json"), builder.getPod(NAMESPACE, OcFixtures.POD_A));
    }

    @Test
    void getEventsCoreListeIstExakt() {
        assertEquals(List.of("get", "events", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                "-o", "json"), builder.getEventsCore(NAMESPACE));
    }

    @Test
    void getEventsEventsK8sIoListeIstExakt() {
        assertEquals(List.of("get", "events.events.k8s.io", "--kubeconfig", KUBECONFIG, "--context", CONTEXT,
                "-n", NAMESPACE, "-o", "json"), builder.getEventsEventsK8sIo(NAMESPACE));
    }

    @Test
    void logsListeIstExaktUndOhneFollow() {
        assertEquals(List.of("logs", OcFixtures.POD_A, "-c", OcFixtures.CONTAINER_MAIN, "--kubeconfig", KUBECONFIG,
                "--context", CONTEXT, "-n", NAMESPACE, "--tail", "1000", "--limit-bytes", "1048576"),
                builder.logs(NAMESPACE, OcFixtures.POD_A, OcFixtures.CONTAINER_MAIN, false, null, 1048576L));
    }

    @Test
    void logsMitPreviousUndSinceTimeListeIstExakt() {
        Instant sinceTime = Instant.parse("2026-10-01T09:56:00Z");
        assertEquals(List.of("logs", OcFixtures.POD_A, "-c", OcFixtures.CONTAINER_MAIN, "--kubeconfig", KUBECONFIG,
                "--context", CONTEXT, "-n", NAMESPACE, "--tail", "50", "--limit-bytes", "250", "--previous",
                "--since-time", "2026-10-01T09:56:00Z"),
                builder.logs(NAMESPACE, OcFixtures.POD_A, OcFixtures.CONTAINER_MAIN, true, sinceTime, 250L, 50));
    }

    @Test
    void keinErzeugterAufrufEnthaeltVerboteneArgumente() {
        Instant sinceTime = Instant.parse("2026-10-01T09:56:00Z");
        List<List<String>> aufrufe = new ArrayList<>(List.of(
                builder.version(),
                builder.whoamiServer(),
                builder.configCurrentContext(),
                builder.getDeployment(NAMESPACE, NAME),
                builder.getDeploymentConfig(NAMESPACE, NAME),
                builder.getReplicaSets(NAMESPACE, SELECTOR),
                builder.getReplicationControllers(NAMESPACE, SELECTOR),
                builder.getPods(NAMESPACE, SELECTOR),
                builder.getPod(NAMESPACE, OcFixtures.POD_A),
                builder.getEventsCore(NAMESPACE),
                builder.getEventsEventsK8sIo(NAMESPACE),
                builder.logs(NAMESPACE, OcFixtures.POD_A, OcFixtures.CONTAINER_MAIN, true, sinceTime, 1024L)));
        List<String> verboten = List.of("exec", "apply", "delete", "patch", "scale", "rollout", "login",
                "project", "attach", "port-forward", "use-context", "set-context", "--follow", "--all-namespaces",
                "create", "replace");
        for (List<String> argumente : aufrufe) {
            for (String argument : argumente) {
                assertFalse(verboten.contains(argument),
                        "Verbotenes Argument '" + argument + "' in " + argumente);
            }
            FakeOc.assertArgumentsAllowed(argumente);
        }
    }

    @Test
    void optionenWerdenNichtUeberLeerzeichenZerlegt() {
        String nameMitLeerzeichen = "fixture workload; touch canary";
        List<String> argumente = builder.getDeployment(NAMESPACE, nameMitLeerzeichen);
        assertEquals(nameMitLeerzeichen, argumente.get(2));
        assertEquals(11, argumente.size(), "Ein Argument bleibt genau ein Listenelement: " + argumente);
    }

    @Test
    void optionsinjektionWirdAbgewiesen() {
        assertThrows(IllegalArgumentException.class, () -> builder.getDeployment(NAMESPACE, "--namespace=other"));
        assertThrows(IllegalArgumentException.class, () -> builder.getPods("-evil", SELECTOR));
        assertThrows(IllegalArgumentException.class, () -> builder.getReplicaSets(NAMESPACE, "--all"));
        assertThrows(IllegalArgumentException.class, () -> builder.getDeployment(NAMESPACE, "mit\nZeilenumbruch"));
    }

    @Test
    void fehlendeKubeconfigOderContextWirdAbgewiesen() {
        assertThrows(IllegalArgumentException.class, () -> new OcCommandBuilder(null, CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> new OcCommandBuilder(KUBECONFIG, " "));
    }
}
