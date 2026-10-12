package ch.so.agi.ops.security;

import ch.so.agi.ops.adapter.openshift.KubeconfigGuard;
import ch.so.agi.ops.adapter.openshift.OcClusterReader;
import ch.so.agi.ops.adapter.openshift.OcParseException;
import ch.so.agi.ops.application.ClockProvider;
import ch.so.agi.ops.application.TargetBuilder;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S08/S09 – falscher kubeconfig-Context, deaktivierte TLS-Pruefung und Credential-Exec-Plugin.
 *
 * <p>Belegt: Die lokale {@code kubeconfig} wird vor jeder Erfassung geprueft. Eine
 * abgeschaltete TLS-Pruefung, ein fremder API-Server und ein {@code exec}-Credential-Plugin
 * werden abgelehnt. Es wird dabei <em>kein</em> Prozess gestartet; der Prozessstarter ist im
 * Test so verdrahtet, dass er den Test scheitern laesst, falls er erreicht wird.</p>
 */
class KubeconfigGuardAcceptanceTest {

    @TempDir
    Path tempDir;

    /** Prozessstarter, der jeden Aufruf als Testfehler meldet und zaehlt. */
    private static final class NeverStartedProcessRunner implements ProcessRunner {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ExecResult exec(String executable, List<String> arguments, Path workingDir,
                Map<String, String> extraEnv, Duration timeout, long maxOutputBytes) {
            calls.incrementAndGet();
            throw new AssertionError("Es darf kein Prozess gestartet werden (S08/S09).");
        }

        int calls() {
            return calls.get();
        }
    }

    private static final String KUBECONFIG_KOPF = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            contexts:
              - name: fixture-context
                context:
                  cluster: fixture-cluster
                  user: fixture-user
                  namespace: fixture-integration
            """;

    private Path gueltigeKubeconfig() {
        return AcceptanceSupport.writeFile(tempDir.resolve("kubeconfig-gueltig.yaml"), KUBECONFIG_KOPF + """
                clusters:
                  - name: fixture-cluster
                    cluster:
                      server: https://api.ocp.so.ch:6443
                users:
                  - name: fixture-user
                    user:
                      token: fixture-token-value
                """);
    }

    private Path kubeconfigMitFremdemServer() {
        return AcceptanceSupport.writeFile(tempDir.resolve("kubeconfig-fremd.yaml"), KUBECONFIG_KOPF + """
                clusters:
                  - name: fixture-cluster
                    cluster:
                      server: https://fremder-api.invalid:6443
                users:
                  - name: fixture-user
                    user:
                      token: fixture-token-value
                """);
    }

    private Path kubeconfigInsecure() {
        return AcceptanceSupport.writeFile(tempDir.resolve("kubeconfig-insecure.yaml"), KUBECONFIG_KOPF + """
                clusters:
                  - name: fixture-cluster
                    cluster:
                      server: https://api.ocp.so.ch:6443
                      insecure-skip-tls-verify: true
                users:
                  - name: fixture-user
                    user:
                      token: fixture-token-value
                """);
    }

    private Path kubeconfigMitExecPlugin() {
        String yaml = KUBECONFIG_KOPF + """
                clusters:
                  - name: fixture-cluster
                    cluster:
                      server: https://api.ocp.so.ch:6443
                users:
                  - name: fixture-user
                    user:
                      exec:
                        apiVersion: client.authentication.k8s.io/v1
                        command: /usr/bin/touch
                        args:
                          - %s
                """.formatted(tempDir.resolve("exec-ausgefuehrt").toAbsolutePath());
        return AcceptanceSupport.writeFile(tempDir.resolve("kubeconfig-exec.yaml"), yaml);
    }

    private SourceResult<ClusterCollection> collectMitKubeconfig(Path kubeconfig, NeverStartedProcessRunner runner) {
        OpsConfig config = AcceptanceSupport.liveConfigMitKubeconfig(tempDir.resolve("ausgabe"), kubeconfig);
        ClockProvider clock = ClockProvider.fixed(AcceptanceSupport.FIXED_NOW);
        ResolvedTarget target = new TargetBuilder(config, clock).build(AcceptanceSupport.APP_ID,
                AcceptanceSupport.ENVIRONMENT,
                new BuildRef(AcceptanceSupport.JENKINS_ID, AcceptanceSupport.JOB_PATH, 42), null, List.of());
        OcClusterReader reader = new OcClusterReader(config, runner, clock.clock(), YamlSupport.jsonMapper());
        CollectionBudget budget = CollectionBudget.of(Duration.ofSeconds(30), AcceptanceSupport.FIXED_NOW, 20, 4, 50,
                8L * 1024 * 1024, 32768);
        return reader.collect(target, budget);
    }

    @Test
    @DisplayName("S08: gueltige kubeconfig wird akzeptiert und der Server passt zur Konfiguration")
    void gueltigeKubeconfigWirdAkzeptiert() {
        KubeconfigGuard.View view = KubeconfigGuard.inspect(gueltigeKubeconfig(), "fixture-context");

        assertEquals("fixture-context", view.configuredContext());
        assertEquals("fixture-context", view.currentContext());
        assertTrue(view.staticIdentity());
        assertTrue(KubeconfigGuard.sameServer(view.server(), AcceptanceSupport.OC_SERVER),
                view.server() + " muss zur konfigurierten Adresse passen.");
    }

    @Test
    @DisplayName("S08: insecure-skip-tls-verify wird abgelehnt")
    void insecureSkipTlsVerifyWirdAbgelehnt() {
        OcParseException error = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfigInsecure(), "fixture-context"));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertTrue(error.getMessage().contains("insecure-skip-tls-verify"), error.getMessage());
    }

    @Test
    @DisplayName("S08: abgeschaltete TLS-Pruefung bricht vor der Live-Datenerfassung ab")
    void abgeschalteteTlsPruefungBrichtVorDerErfassungAb() {
        NeverStartedProcessRunner runner = new NeverStartedProcessRunner();

        SourceResult<ClusterCollection> result = collectMitKubeconfig(kubeconfigInsecure(), runner);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertFalse(result.errors().isEmpty());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertEquals(0, runner.calls(), "Es darf kein oc-Prozess gestartet werden.");
    }

    @Test
    @DisplayName("S08: fremder API-Server in der kubeconfig wird abgelehnt")
    void fremderApiServerWirdAbgelehnt() {
        KubeconfigGuard.View view = KubeconfigGuard.inspect(kubeconfigMitFremdemServer(), "fixture-context");

        assertFalse(KubeconfigGuard.sameServer(view.server(), AcceptanceSupport.OC_SERVER));
        NeverStartedProcessRunner runner = new NeverStartedProcessRunner();
        SourceResult<ClusterCollection> result = collectMitKubeconfig(kubeconfigMitFremdemServer(), runner);
        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(result.errors().get(0).message().contains("kein oc-Aufruf"), result.errors().toString());
        assertEquals(0, runner.calls());
    }

    @Test
    @DisplayName("S08: unbekannter Context wird abgelehnt, ein Defaultcontext wird nicht geraten")
    void unbekannterContextWirdAbgelehnt() {
        Path kubeconfig = gueltigeKubeconfig();

        OcParseException error = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, "fremder-context"));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.code());
    }

    @Test
    @DisplayName("S09: exec-Credential-Plugin wird abgelehnt, ohne ein Programm auszufuehren")
    void execCredentialPluginWirdAbgelehnt() {
        OcParseException error = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfigMitExecPlugin(), "fixture-context"));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertTrue(error.getMessage().contains("exec-Credential-Plugin"), error.getMessage());
        assertFalse(Files.exists(tempDir.resolve("exec-ausgefuehrt")),
                "Das Credential-Programm darf nicht ausgefuehrt worden sein.");
    }

    @Test
    @DisplayName("S09: exec-Plugin startet keinen Prozess und keine Live-Erfassung")
    void execPluginStartetKeinenProzess() {
        NeverStartedProcessRunner runner = new NeverStartedProcessRunner();

        SourceResult<ClusterCollection> result = collectMitKubeconfig(kubeconfigMitExecPlugin(), runner);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertEquals(0, runner.calls(), "Es darf kein Prozess gestartet werden.");
        assertFalse(Files.exists(tempDir.resolve("exec-ausgefuehrt")),
                "Das im exec-Block genannte Programm darf nicht ausgefuehrt worden sein.");
    }

    @Test
    @DisplayName("S08/S09: Gegenprobe - bei gueltiger kubeconfig wird der Prozessstarter tatsaechlich erreicht")
    void gegenprobeGueltigeKubeconfigErreichtDenProzessstarter() {
        NeverStartedProcessRunner runner = new NeverStartedProcessRunner();

        assertThrows(AssertionError.class, () -> collectMitKubeconfig(gueltigeKubeconfig(), runner));

        assertTrue(runner.calls() >= 1, "Bei gueltiger kubeconfig muss der Prozessstarter erreicht werden; "
                + "sonst waere die Gegenprobe wertlos.");
    }
}
