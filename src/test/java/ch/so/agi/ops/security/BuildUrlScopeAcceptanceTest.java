package ch.so.agi.ops.security;

import ch.so.agi.ops.adapter.jenkins.JenkinsUrlBuilder;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.mcp.OpsTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S02 (a-c) – Fremde Build-URL, Userinfo und manipulierte Jobsegmente.
 *
 * <p>Belegt: Jobpfadsegmente mit Pfadtrennern, Pfadwechsel, Steuerzeichen oder leerem Inhalt
 * werden abgewiesen; eine Build-URL mit fremdem Host oder mit Userinfo ergibt
 * {@code SCOPE_DENIED}; ein Hostwechsel ist kein Erfolgsfall (keine SSRF).</p>
 */
class BuildUrlScopeAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final String VALID_CONSOLE_URL = AcceptanceSupport.JENKINS_BASE_URL
            + "/job/fixture-folder/job/fixture-app/42/consoleText";

    @Test
    @DisplayName("S02: Jobpfadsegmente mit Pfadtrenner, Pfadwechsel, Steuerzeichen oder leer werden abgewiesen")
    void unzulaessigeJobsegmenteWerdenAbgewiesen() {
        List<String> verboten = List.of("a/b", "..", "...", "a\\b", "", "   ", "a\tb", "a\nb", "a\u0000b",
                "x".repeat(JenkinsUrlBuilder.MAX_SEGMENT_LENGTH + 1), " leading", "trailing ", "https://fremd.invalid",
                "/absolut");

        for (String segment : verboten) {
            ScopeViolationException error = assertThrows(ScopeViolationException.class,
                    () -> JenkinsUrlBuilder.encodeSegment(segment),
                    "Segment muss abgewiesen werden: " + PathSafety.describe(segment));
            assertEquals(ErrorCode.SCOPE_DENIED, error.code(), "Segment: " + PathSafety.describe(segment));
        }
    }

    @Test
    @DisplayName("S02: manipulierte Jobpfade erzeugen keine fremde URL")
    void manipulierteJobpfadeErzeugenKeineFremdeUrl() {
        List<List<String>> verboten = List.of(
                List.of("..", "etc"),
                List.of("fixture-folder", "../../../etc"),
                List.of("fixture-folder", "a/b"),
                List.of(""),
                List.of("fixture-folder", "back\\slash"));

        for (List<String> jobPath : verboten) {
            ScopeViolationException error = assertThrows(ScopeViolationException.class,
                    () -> JenkinsUrlBuilder.consoleTextUrl(AcceptanceSupport.JENKINS_BASE_URL, jobPath, 42),
                    "Jobpfad muss abgewiesen werden: " + jobPath);
            assertEquals(ErrorCode.SCOPE_DENIED, error.code());
        }
    }

    @Test
    @DisplayName("S02: gueltiger Jobpfad ergibt genau die URL der konfigurierten Instanz")
    void gueltigerJobpfadErgibtKonfigurierteInstanz() {
        String url = JenkinsUrlBuilder.consoleTextUrl(AcceptanceSupport.JENKINS_BASE_URL,
                List.of("fixture ordner", "fixture-app"), 42);

        assertEquals(AcceptanceSupport.JENKINS_BASE_URL
                + "/job/fixture%20ordner/job/fixture-app/42/consoleText", url);
        assertTrue(url.startsWith(AcceptanceSupport.JENKINS_BASE_URL));
    }

    @Test
    @DisplayName("S02: Basisadresse mit Userinfo wird abgewiesen")
    void basisadresseMitUserinfoWirdAbgewiesen() {
        ScopeViolationException error = assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireBase("https://fixture-user:fixture-pass@"
                        + "jenkins-agi-apps-production.apps.ocp.so.ch"));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
    }

    @Test
    @DisplayName("S02: Build-URL mit fremdem Host ergibt SCOPE_DENIED ohne Netzaufruf")
    void fremderHostErgibtScopeDenied() {
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir);
        OpsTools tools = AcceptanceSupport.harness(config).tools();

        OpsTools.OpsToolException error = assertThrows(OpsTools.OpsToolException.class,
                () -> tools.resolveTarget(Map.of("applicationId", AcceptanceSupport.APP_ID, "environment",
                        AcceptanceSupport.ENVIRONMENT, "build", Map.of("kind", "number", "number", 42), "buildUrl",
                        "https://fremder-host.invalid/job/fixture-folder/job/fixture-app/42/")));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
    }

    @Test
    @DisplayName("S02: Build-URL mit Userinfo wird abgewiesen")
    void buildUrlMitUserinfoWirdAbgewiesen() {
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir);
        OpsTools tools = AcceptanceSupport.harness(config).tools();

        OpsTools.OpsToolException error = assertThrows(OpsTools.OpsToolException.class,
                () -> tools.resolveTarget(Map.of("applicationId", AcceptanceSupport.APP_ID, "environment",
                        AcceptanceSupport.ENVIRONMENT, "build", Map.of("kind", "number", "number", 42), "buildUrl",
                        "https://fixture-user:fixture-pass@jenkins-agi-apps-production.apps.ocp.so.ch"
                                + "/job/fixture-folder/job/fixture-app/42/")));

        assertEquals(ErrorCode.SCOPE_DENIED, error.code());
    }

    @Test
    @DisplayName("S02: Gegenprobe - der Link der konfigurierten Instanz wird akzeptiert, aber nicht verwendet")
    void linkDerKonfiguriertenInstanzWirdAkzeptiert() {
        OpsConfig config = AcceptanceSupport.inMemoryConfig(tempDir);
        OpsTools tools = AcceptanceSupport.harness(config).tools();

        Map<String, Object> result = tools.resolveTarget(Map.of("applicationId", AcceptanceSupport.APP_ID,
                "environment", AcceptanceSupport.ENVIRONMENT, "build", Map.of("kind", "number", "number", 42),
                "buildUrl", VALID_CONSOLE_URL));

        assertEquals(AcceptanceSupport.NAMESPACE, result.get("namespace"));
        assertEquals(42, result.get("buildNumber"));
        assertTrue(String.valueOf(result.get("warnings")).contains("nicht als Netzwerkziel"),
                String.valueOf(result.get("warnings")));
    }
}
