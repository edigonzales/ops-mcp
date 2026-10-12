package ch.so.agi.ops.security;

import ch.so.agi.ops.adapter.jenkins.JenkinsHttpReader;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S02 (d) – eine Weiterleitung auf einen fremden Host gibt keinen {@code Authorization}-Header
 * weiter.
 *
 * <p>Der echte Jenkins-Adapter wird gegen zwei lokale Testserver auf {@code 127.0.0.1}
 * betrieben. Der erste antwortet mit einer Weiterleitung auf den zweiten, der als fremde
 * Origin gilt. Belegt wird: Die Weiterleitung wird abgewiesen (SCOPE_DENIED, Hinweis S-02),
 * der fremde Server erhaelt <em>keine</em> Anfrage und damit auch keinen Auth-Header.</p>
 */
class JenkinsRedirectAcceptanceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("S02: Weiterleitung auf fremde Origin wird abgewiesen, ohne den Auth-Header zu senden")
    void weiterleitungAufFremdeOriginWirdAbgewiesen() {
        try (FixtureHttpServer fremd = FixtureHttpServer.serving("fremder-inhalt".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
                FixtureHttpServer jenkins = FixtureHttpServer.redirectingTo(
                        fremd.baseUrl() + "/job/fixture-folder/job/fixture-app/42/consoleText")) {

            OpsConfig config = AcceptanceSupport.jenkinsConfig(tempDir.resolve("ausgabe"), jenkins.baseUrl(),
                    ch.so.agi.ops.config.Limits.defaults().maxBuildLogBytes());
            SourceResult<ConsoleLogEvidence> result = AcceptanceSupport.consoleLog(config);

            assertFalse(result.errors().isEmpty(), "Die Weiterleitung muss abgewiesen werden.");
            assertEquals(ErrorCode.SCOPE_DENIED, result.errors().getFirst().code());
            assertTrue(result.errors().getFirst().message().contains("S-02"),
                    result.errors().getFirst().message());
            assertEquals(SourceStatus.NOT_ALLOWED, result.status());

            assertEquals(0, fremd.requestCount(),
                    "Der fremde Host darf gar nicht kontaktiert worden sein.");
            assertFalse(fremd.anyAuthorizationHeaderReceived(),
                    "Es darf kein Authorization-Header an den fremden Host gelangen.");
            assertTrue(jenkins.requestCount() >= 1, "Der konfigurierte Host wurde kontaktiert.");
        }
    }

    @Test
    @DisplayName("S02: der konfigurierte Host erhaelt den Auth-Header, ein fremder Host nicht")
    void konfigurierterHostErhaeltAuthHeader() {
        byte[] log = AcceptanceSupport.consoleLogMitFehlerAmEnde(50, "ERROR: Fixture");
        try (FixtureHttpServer jenkins = FixtureHttpServer.serving(log)) {
            OpsConfig config = AcceptanceSupport.jenkinsConfig(tempDir.resolve("ausgabe"), jenkins.baseUrl(),
                    ch.so.agi.ops.config.Limits.defaults().maxBuildLogBytes());

            SourceResult<ConsoleLogEvidence> result = new JenkinsHttpReader(config, AcceptanceSupport.JENKINS_ID,
                    ch.so.agi.ops.application.ClockProvider.fixed(AcceptanceSupport.FIXED_NOW).clock(),
                    YamlSupport.jsonMapper()).collectConsoleLog(AcceptanceSupport.targetForJenkins(config),
                            AcceptanceSupport.budget());

            assertEquals(SourceStatus.COLLECTED, result.status(), result.errors().toString());
            assertTrue(jenkins.anyAuthorizationHeaderReceived(),
                    "Gegenueber der konfigurierten Instanz wird der Auth-Header gesendet.");
        }
    }
}
