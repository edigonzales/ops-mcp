package ch.so.agi.ops.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests der CLI-Oberflaeche: Argumentpruefung, Exitcodes, Modustrennung und die Abwesenheit
 * von aendernden Befehlen.
 *
 * <p>Die Tests verwenden ausschliesslich lokale, synthetische Konfigurationen und rufen
 * keine Quellsysteme auf.</p>
 */
class CliRunnerTest {

    @TempDir
    Path tempDir;

    private record Run(int exitCode, String out, String err) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
        int exitCode = OpsAgentMain.runForTesting(args, out, err);
        out.flush();
        err.flush();
        return new Run(exitCode, outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8));
    }

    private Path configFile(String content) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String fixtureConfig() {
        String root = tempDir.toAbsolutePath().toString();
        return """
                schemaVersion: 1
                mode: fixture
                storage:
                  root: "STATE"
                security:
                  allowPersonalCredentials: false
                  modelDataPolicy: internal-approved-only
                  identityMode: restricted
                limits: {}
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    context: "fixture-context"
                    allowedNamespaces:
                      - fixture-integration
                jenkins:
                  apps-deploy:
                    baseUrl: "https://jenkins.fixture.invalid"
                    token:
                      environmentVar: OPS_AGENT_FIXTURE_TOKEN
                    allowedJobPaths:
                      - ["fixture-folder", "modelfinder"]
                repositories:
                  deployment-config:
                    repositoryId: sogis/openshift-templates
                    localPath: "REPO"
                    allowedFiles:
                      - templates/modelfinder.yaml
                applications:
                  modelfinder:
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: fixture-integration
                        workload:
                          kind: Deployment
                          name: fixture-modelfinder
                          containerNames: [modelfinder]
                        deployment:
                          jenkins: apps-deploy
                          jobPath: ["fixture-folder", "modelfinder"]
                        configuration:
                          repository: deployment-config
                          templateFiles:
                            - templates/modelfinder.yaml
                          revision:
                            source: jenkins-scm
                            repositoryId: sogis/openshift-templates
                fixture:
                  root: "FIXTURES"
                  scenario: t01-liveness-startup
                  clock: fixed
                  fixedNow: "2026-10-01T10:10:00Z"
                """
                .replace("STATE", tempDir.resolve("state").toString())
                .replace("REPO", tempDir.resolve("repo").toString())
                .replace("FIXTURES", tempDir.resolve("fixtures").toString())
                .replace(root, root);
    }

    @Test
    @DisplayName("Kein Befehl und --help liefern die Verwendung mit Exitcode 0")
    void usageIsShown() {
        Run help = run("--help");
        assertEquals(ExitCodes.OK, help.exitCode());
        assertTrue(help.out().contains("Verwendung"));
        assertTrue(help.out().contains("Es gibt keine Befehle zum Aendern"));
    }

    @Test
    @DisplayName("Aendernde Befehle existieren nicht")
    void mutatingCommandsDoNotExist() {
        for (String command : new String[] { "deploy", "fix", "apply", "rollback", "trigger", "delete", "patch" }) {
            Run result = run("--config", "egal.yaml", command);
            assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode(),
                    "Der Befehl '" + command + "' darf nicht existieren.");
            assertTrue(result.err().contains("unbekannter Befehl"), result.err());
        }
        assertFalse(CliArguments.USAGE.contains("ops-agent --config <datei> [--json] deploy"));
    }

    @Test
    @DisplayName("Fehlende Konfigurationsdatei ergibt Exitcode 2")
    void missingConfigFile() {
        Run result = run("--config", tempDir.resolve("fehlt.yaml").toString(), "doctor");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("CONFIG_INVALID"), result.err());
    }

    @Test
    @DisplayName("Fehlende --config-Option ergibt Exitcode 2")
    void missingConfigOption() {
        Run result = run("doctor");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("--config"));
    }

    @Test
    @DisplayName("Ungueltige Konfiguration wird mit Exitcode 2 und Feldliste gemeldet")
    void invalidConfigIsReported() throws IOException {
        Path file = configFile("""
                schemaVersion: 1
                mode: fixture
                unbekannt: true
                """);
        Run result = run("--config", file.toString(), "doctor");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("unbekannt"), result.err());
    }

    @Test
    @DisplayName("Unvollstaendiger Anwendungseintrag blockiert den Auftrag, nicht den Fixturebetrieb")
    void incompleteApplicationIsReported() throws IOException {
        // Ein vorhandenes, aber fachlich leeres Szenario: doctor muss bis zur
        // Konfigurationspruefung kommen, ohne am Fixture-Wurzelverzeichnis zu scheitern.
        Path scenarioRoot = tempDir.resolve("scenarios");
        Files.createDirectories(scenarioRoot.resolve("minimal"));
        Files.writeString(scenarioRoot.resolve("minimal").resolve("scenario.json"),
                "{\"id\": \"minimal\", \"title\": \"leer\", \"synthetic\": true, \"expectation\": \"keine\"}",
                StandardCharsets.UTF_8);
        Path base = configFile(fixtureConfig().replace("namespace: fixture-integration", "namespace: null"));
        String content = Files.readString(base, StandardCharsets.UTF_8)
                .replace("root: \"" + tempDir.resolve("fixtures") + "\"",
                        "root: \"" + scenarioRoot + "\"")
                .replace("scenario: t01-liveness-startup", "scenario: minimal");
        Path file = configFile(content);
        Run doctor = run("--config", file.toString(), "doctor");
        assertEquals(ExitCodes.OK, doctor.exitCode(), "doctor muss auch mit unvollstaendigem Eintrag laufen: "
                + doctor.err());
        Run collect = run("--config", file.toString(), "collect", "--application", "modelfinder",
                "--environment", "integration", "--build", "42");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, collect.exitCode());
        assertTrue(collect.err().contains("CONFIG_INCOMPLETE"), collect.err());
        assertTrue(collect.err().contains("namespace"), collect.err());
    }

    @Test
    @DisplayName("applications listet Anwendungen und Umgebungen mit Konfigurationsstatus")
    void applicationsAreListed() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "applications");
        assertEquals(ExitCodes.OK, result.exitCode(), result.err());
        assertTrue(result.out().contains("modelfinder"));
        assertTrue(result.out().contains("integration"));
    }

    @Test
    @DisplayName("applications --application mit unbekannter Kennung ergibt Exitcode 2")
    void unknownApplicationIsRejected() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "applications", "--application", "gibt-es-nicht");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("Unbekannte Anwendung"));
    }

    @Test
    @DisplayName("--build und --last-failed schliessen sich aus")
    void buildAndLastFailedAreMutuallyExclusive() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "collect", "--application", "modelfinder",
                "--environment", "integration", "--build", "42", "--last-failed");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("nicht gemeinsam"), result.err());
    }

    @Test
    @DisplayName("Ohne Buildauswahl wird nichts geraten")
    void buildSelectionIsRequired() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "collect", "--application", "modelfinder",
                "--environment", "integration");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("--build"), result.err());
    }

    @Test
    @DisplayName("Ungueltige Buildnummer wird abgewiesen")
    void invalidBuildNumberIsRejected() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "collect", "--application", "modelfinder",
                "--environment", "integration", "--build", "abc");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("positive Ganzzahl"));
    }

    @Test
    @DisplayName("--json ist bei serve nicht zulaessig, weil stdout JSON-RPC bleibt")
    void jsonIsNotAllowedForServe() {
        Run result = run("--config", "egal.yaml", "--json", "serve");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("JSON-RPC"), result.err());
    }

    @Test
    @DisplayName("collect in einem Szenario ohne Fixture-Reader scheitert erklaert und nicht still")
    void collectWithoutFixtureScenarioFails() throws IOException {
        Path file = configFile(fixtureConfig().replace("root: \"" + tempDir.resolve("fixtures") + "\"",
                "root: \"" + tempDir.resolve("gibt-es-nicht") + "\""));
        Run result = run("--config", file.toString(), "collect", "--application", "modelfinder",
                "--environment", "integration", "--build", "42");
        assertTrue(result.exitCode() != ExitCodes.OK, "Ohne Szenario darf kein Erfolg gemeldet werden.");
    }

    @Test
    @DisplayName("inspect mit unzulaessiger Snapshot-ID ergibt Exitcode 2")
    void inspectWithInvalidSnapshotId() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "inspect", "--snapshot", "../../etc/passwd");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
    }

    @Test
    @DisplayName("report mit fehlender Eingabedatei ergibt Exitcode 2")
    void reportWithMissingInput() throws IOException {
        Path file = configFile(fixtureConfig());
        Run result = run("--config", file.toString(), "report", "--snapshot", "20261001T101000Z-abcdefgh",
                "--input", tempDir.resolve("fehlt.json").toString());
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("nicht gefunden"), result.err());
    }

    @Test
    @DisplayName("Unbekannte Option wird abgewiesen")
    void unknownOptionIsRejected() {
        Run result = run("--config", "x.yaml", "--cluster-admin", "doctor");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("--cluster-admin"), result.err());
    }

    @Test
    @DisplayName("Option ohne Wert wird abgewiesen")
    void optionWithoutValueIsRejected() {
        Run result = run("--config");
        assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, result.exitCode());
        assertTrue(result.err().contains("braucht einen Wert"), result.err());
    }

    @Test
    @DisplayName("Exitcodes sind dokumentiert")
    void exitCodesAreDocumented() {
        assertEquals(0, ExitCodes.OK);
        assertEquals(2, ExitCodes.INVALID_INPUT_OR_CONFIG);
        assertEquals(3, ExitCodes.SOURCE_UNAVAILABLE);
        assertEquals(4, ExitCodes.PARTIAL_SNAPSHOT);
        assertEquals(5, ExitCodes.REPORT_INVALID);
        assertEquals(6, ExitCodes.SCOPE_VIOLATION);
        assertTrue(ExitCodes.describe(ExitCodes.PARTIAL_SNAPSHOT).contains("partiell"));
    }
}
