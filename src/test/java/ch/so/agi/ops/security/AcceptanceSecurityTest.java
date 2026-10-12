package ch.so.agi.ops.security;

import ch.so.agi.ops.application.TargetResolutionException;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ConfigException;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S01 – Namespace/Workload ausserhalb der Allowlist.
 *
 * <p>Belegt: Ein Namespace oder Jobpfad ausserhalb der konfigurierten oberen Grenze ergibt
 * {@code SCOPE_DENIED}; der strenge {@code ConfigLoader} laesst eine Anwendung nicht zu, die
 * die obere Grenze erweitert; und es erfolgt <em>kein</em> Quellaufruf.</p>
 */
class AcceptanceSecurityTest {

    @TempDir
    Path tempDir;

    /** Quelladapter, der jeden Aufruf als Testfehler meldet. */
    private static final class NeverCalledClusterReader implements ClusterReader {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public SourceResult<ClusterCollection> collect(ResolvedTarget target, CollectionBudget budget) {
            calls.incrementAndGet();
            throw new AssertionError("Der ClusterReader darf hier nie aufgerufen werden (kein Quellaufruf).");
        }

        @Override
        public String sourceId() {
            return AcceptanceSupport.CLUSTER_ID;
        }

        int calls() {
            return calls.get();
        }
    }

    @Test
    @DisplayName("S01: Namespace ausserhalb clusters.allowedNamespaces ergibt SCOPE_DENIED")
    void namespaceAusserhalbAllowlist() {
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir, "fremder-namespace",
                List.of(AcceptanceSupport.NAMESPACE), AcceptanceSupport.JOB_PATH, List.of(AcceptanceSupport.JOB_PATH));

        TargetResolutionException error = assertThrows(TargetResolutionException.class,
                () -> AcceptanceSupport.harness(config).resolveTarget());

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertTrue(error.getMessage().contains("nicht freigegeben"), error.getMessage());
    }

    @Test
    @DisplayName("S01: Jobpfad ausserhalb jenkins.allowedJobPaths ergibt SCOPE_DENIED")
    void jobPfadAusserhalbAllowlist() {
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir, AcceptanceSupport.NAMESPACE,
                List.of(AcceptanceSupport.NAMESPACE), List.of("fremder-folder", "fremder-job"),
                List.of(AcceptanceSupport.JOB_PATH));

        TargetResolutionException error = assertThrows(TargetResolutionException.class,
                () -> AcceptanceSupport.harness(config).resolveTarget());

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertTrue(error.getMessage().contains("Jobpfad"), error.getMessage());
    }

    @Test
    @DisplayName("S01: ConfigLoader akzeptiert die eingehaltene obere Grenze")
    void configLoaderAkzeptiertEingehalteneGrenze() {
        Path base = tempDir.resolve("config-gueltig");
        Path file = AcceptanceSupport.writeFile(base.resolve("config.yaml"), AcceptanceSupport.configYaml(base,
                AcceptanceSupport.NAMESPACE, List.of(AcceptanceSupport.NAMESPACE), AcceptanceSupport.JOB_PATH,
                List.of(AcceptanceSupport.JOB_PATH)));

        OpsConfig config = AcceptanceSupport.loadConfig(file);

        assertTrue(config.application(AcceptanceSupport.APP_ID) != null);
        assertEquals(AcceptanceSupport.NAMESPACE,
                config.application(AcceptanceSupport.APP_ID).environment(AcceptanceSupport.ENVIRONMENT).namespace());
    }

    @Test
    @DisplayName("S01: ConfigLoader lehnt einen Namespace ab, der die obere Grenze erweitert")
    void configLoaderLehntNamespaceErweiterungAb() {
        Path base = tempDir.resolve("config-namespace");
        Path file = AcceptanceSupport.writeFile(base.resolve("config.yaml"), AcceptanceSupport.configYaml(base,
                "fremder-namespace", List.of(AcceptanceSupport.NAMESPACE), AcceptanceSupport.JOB_PATH,
                List.of(AcceptanceSupport.JOB_PATH)));

        ConfigException error = assertThrows(ConfigException.class, () -> AcceptanceSupport.loadConfig(file));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.code() == ErrorCode.SCOPE_DENIED),
                error.problems().toString());
        assertTrue(error.problems().stream()
                .anyMatch(problem -> problem.message().contains("allowedNamespaces")), error.problems().toString());
    }

    @Test
    @DisplayName("S01: ConfigLoader lehnt einen Jobpfad ab, der die obere Grenze erweitert")
    void configLoaderLehntJobpfadErweiterungAb() {
        Path base = tempDir.resolve("config-jobpfad");
        Path file = AcceptanceSupport.writeFile(base.resolve("config.yaml"), AcceptanceSupport.configYaml(base,
                AcceptanceSupport.NAMESPACE, List.of(AcceptanceSupport.NAMESPACE), List.of("fremder-folder", "x"),
                List.of(AcceptanceSupport.JOB_PATH)));

        ConfigException error = assertThrows(ConfigException.class, () -> AcceptanceSupport.loadConfig(file));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.code() == ErrorCode.SCOPE_DENIED),
                error.problems().toString());
        assertTrue(error.problems().stream()
                .anyMatch(problem -> problem.message().contains("allowedJobPaths")), error.problems().toString());
    }

    @Test
    @DisplayName("S01: abgewiesenes Ziel loest keinen Quellaufruf aus")
    void abgewiesenesZielLoestKeinenQuellaufrufAus() {
        NeverCalledClusterReader reader = new NeverCalledClusterReader();
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir, "fremder-namespace",
                List.of(AcceptanceSupport.NAMESPACE), AcceptanceSupport.JOB_PATH,
                List.of(AcceptanceSupport.JOB_PATH));
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(config, reader,
                config.limits().maxEvidenceResponseBytes());

        ch.so.agi.ops.mcp.OpsTools.OpsToolException error = assertThrows(
                ch.so.agi.ops.mcp.OpsTools.OpsToolException.class,
                () -> harness.tools().resolveTarget(Map.of("applicationId", AcceptanceSupport.APP_ID,
                        "environment", AcceptanceSupport.ENVIRONMENT, "build", Map.of("kind", "number", "number", 42))));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        assertEquals(0, reader.calls(), "Es darf kein einziger Quellaufruf erfolgen.");
        assertFalse(Files.exists(tempDir.resolve("snapshots")),
                "Ein abgewiesenes Ziel darf keinen Snapshot anlegen.");
    }

    @Test
    @DisplayName("S01: Gegenprobe - im erlaubten Fall wird derselbe Quelladapter tatsaechlich aufgerufen")
    void gegenprobeErlaubterFallRuftQuelladapterAuf() {
        NeverCalledClusterReader reader = new NeverCalledClusterReader();
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir);
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(config, reader,
                config.limits().maxEvidenceResponseBytes());

        // Nur die Verdrahtung wird geprueft: der Reader wirft beim ersten Aufruf einen Fehler,
        // der hier erwartet und als Beweis verwendet wird.
        Map<String, Object> target = harness.tools().resolveTarget(Map.of("applicationId",
                AcceptanceSupport.APP_ID, "environment", AcceptanceSupport.ENVIRONMENT, "build",
                Map.of("kind", "number", "number", 42)));
        assertThrows(AssertionError.class, () -> harness.tools()
                .collectSnapshot(Map.of("targetId", String.valueOf(target.get("targetId")))));

        assertEquals(1, reader.calls(),
                "Im erlaubten Fall muss der Quelladapter erreicht werden; sonst waere die Gegenprobe wertlos.");
    }
}
