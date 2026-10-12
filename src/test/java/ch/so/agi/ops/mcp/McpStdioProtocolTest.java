package ch.so.agi.ops.mcp;

import ch.so.agi.ops.snapshot.SnapshotId;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Echter MCP-Protokolltest ueber stdio.
 *
 * <p>Startet den gebauten Launcher als Kindprozess, initialisiert einen echten SDK-Client,
 * listet und ruft alle sechs Werkzeuge auf. stdout des Kindprozesses enthaelt ausschliesslich
 * JSON-RPC; die Diagnose des Kindes geht nach stderr. Ein direkter Handler-Unit-Test waere
 * hierfuer kein Ersatz.</p>
 */
class McpStdioProtocolTest {

    private static final List<String> EXPECTED_TOOLS = List.of(
            "ops_list_applications", "ops_resolve_target", "ops_collect_snapshot",
            "ops_get_snapshot", "ops_read_evidence", "ops_store_report");

    @TempDir
    Path tempDir;

    private Path configFile;
    private Path storageRoot;
    private McpSyncClient client;
    private StdioClientTransport transport;
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Path launcher = Path.of(System.getProperty("opsagent.launcher", ""));
        Assumptions.assumeTrue(Files.isExecutable(launcher),
                "Der installDist-Launcher ist nicht vorhanden; Test wird uebersprungen.");
        Path projectDir = Path.of(System.getProperty("opsagent.projectDir", "."));
        Path scenarioRoot = projectDir.resolve("fixtures");
        Assumptions.assumeTrue(Files.isDirectory(scenarioRoot),
                "Die Fixture-Szenarien sind nicht vorhanden; Test wird uebersprungen.");
        Assumptions.assumeTrue(Files.isDirectory(scenarioRoot.resolve("t01-liveness-startup")),
                "Das Szenario t01-liveness-startup fehlt; Test wird uebersprungen.");
        Assumptions.assumeTrue(isGitAvailable(), "git ist nicht verfuegbar; Test wird uebersprungen.");

        storageRoot = tempDir.resolve("state");
        Files.createDirectories(storageRoot);
        configFile = tempDir.resolve("fixture.yaml");
        Files.writeString(configFile, fixtureConfig(scenarioRoot, storageRoot), StandardCharsets.UTF_8);

        McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
        ServerParameters parameters = ServerParameters.builder(launcher.toString())
                .args("--config", configFile.toString(), "serve")
                .build();
        transport = new StdioClientTransport(parameters, mapper);
        transport.setStdErrorHandler(stderrLines::add);
        client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("ops-agent-test-client", "1.0.0").build())
                .requestTimeout(Duration.ofSeconds(120))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // Beim Schliessen sind Fehler unkritisch.
            }
        }
    }

    @Test
    @DisplayName("Protokollinitialisierung nennt Server und Anweisung")
    void initializeNegotiatesServer() {
        assertTrue(client.isInitialized(), "Der Client muss initialisiert sein.");
        assertNotNull(client.getServerInfo(), "Der Server muss seine Implementierung nennen.");
        assertEquals(McpStdioServer.SERVER_NAME, client.getServerInfo().name());
        assertNotNull(client.getServerInstructions());
        assertTrue(client.getServerInstructions().contains("ops_"),
                "Die Serveranweisung muss auf die ops_*-Werkzeuge verweisen.");
        assertNotNull(client.getCurrentInitializationResult());
        assertNotNull(client.getCurrentInitializationResult().protocolVersion(),
                "Die verhandelte Protokollversion muss ausgewiesen sein.");

        // Beleg fuer die Abnahme: die tatsaechlich verhandelte Protokollversion und die
        // SDK-Version werden ausgegeben. Es wird keine zukuenftige Protokollversion behauptet.
        System.out.println("MCP-NACHWEIS verhandelteProtokollversion="
                + client.getCurrentInitializationResult().protocolVersion()
                + " server=" + client.getServerInfo().name() + "/" + client.getServerInfo().version()
                + " " + McpStdioServer.protocolInformation());
    }

    @Test
    @DisplayName("tools/list liefert genau die sechs Werkzeuge mit Schemas und Annotationen")
    void listsExactlySixTools() {
        McpSchema.ListToolsResult tools = client.listTools();
        assertNotNull(tools);
        List<String> names = new ArrayList<>();
        tools.tools().forEach(tool -> names.add(tool.name()));
        assertEquals(EXPECTED_TOOLS.size(), names.size(), "Es muessen genau sechs Werkzeuge sein: " + names);
        assertTrue(names.containsAll(EXPECTED_TOOLS), "Fehlende Werkzeuge: " + names);
        for (McpSchema.Tool tool : tools.tools()) {
            assertNotNull(tool.description(), tool.name() + " braucht eine Beschreibung.");
            assertNotNull(tool.inputSchema(), tool.name() + " braucht ein Eingabeschema.");
            assertNotNull(tool.outputSchema(), tool.name() + " braucht ein Ausgabeschema.");
            assertNotNull(tool.annotations(), tool.name() + " braucht Annotationen.");
            assertEquals(Boolean.valueOf(expectedReadOnly(tool.name())), tool.annotations().readOnlyHint(),
                    tool.name() + ": readOnlyHint falsch.");
            assertEquals(Boolean.FALSE, tool.annotations().destructiveHint(),
                    tool.name() + ": destructiveHint muss false sein.");
        }
    }

    @Test
    @DisplayName("Vollstaendiger Ablauf: Anwendungen, Ziel, Snapshot, Evidence, Bericht")
    void fullFixtureInvestigation() {
        Map<String, Object> applications = call("ops_list_applications", Map.of());
        assertEquals("fixture", applications.get("dataMode"));
        assertNotNull(applications.get("applications"));

        Map<String, Object> target = call("ops_resolve_target", Map.of(
                "applicationId", "modelfinder",
                "environment", "integration",
                "build", Map.of("kind", "number", "number", 42)));
        String targetId = String.valueOf(target.get("targetId"));
        assertTrue(targetId.startsWith("TGT-"), "targetId muss serverseitig erzeugt sein.");
        assertEquals(42, ((Number) target.get("buildNumber")).intValue());

        Map<String, Object> snapshot = call("ops_collect_snapshot", Map.of("targetId", targetId));
        String snapshotId = String.valueOf(snapshot.get("snapshotId"));
        assertNotNull(SnapshotId.of(snapshotId));
        assertTrue(List.of("complete", "partial").contains(String.valueOf(snapshot.get("collectionStatus"))),
                "Sammelstatus: " + snapshot.get("collectionStatus"));

        Map<String, Object> read = call("ops_get_snapshot", Map.of("snapshotId", snapshotId));
        assertEquals(snapshotId, read.get("snapshotId"));
        List<?> evidenceIndex = (List<?>) read.get("evidenceIndex");
        assertNotNull(evidenceIndex);
        assertFalse(evidenceIndex.isEmpty(), "Der Snapshot muss Evidence enthalten.");
        assertNotNull(read.get("links"));
        assertNotNull(read.get("sourceResults"));

        String firstEvidenceId = String.valueOf(((Map<?, ?>) evidenceIndex.get(0)).get("id"));
        Map<String, Object> evidence = call("ops_read_evidence", Map.of(
                "snapshotId", snapshotId,
                "evidenceId", firstEvidenceId,
                "startLine", 1,
                "maxLines", 50));
        assertNotNull(evidence.get("text"));
        assertTrue(String.valueOf(evidence.get("text")).length() > 0, "Evidence-Text darf nicht leer sein.");
        assertNotNull(evidence.get("readBudget"));

        Map<String, Object> diagnosis = insufficientEvidenceReport(snapshotId);
        Map<String, Object> report = call("ops_store_report", Map.of(
                "snapshotId", snapshotId,
                "diagnosis", diagnosis));
        assertNotNull(report.get("reportId"));
        assertTrue(String.valueOf(report.get("markdownPath")).startsWith("reports/"),
                "Der Bericht muss unter reports/ liegen: " + report.get("markdownPath"));
        assertTrue(Files.isRegularFile(storageRoot.resolve(String.valueOf(report.get("markdownPath")))),
                "Die Markdown-Datei muss existieren.");

        // Ein zweiter Aufruf darf keinen fremden Snapshot referenzieren.
        McpSchema.CallToolResult failure = client.callTool(callToolRequest("ops_store_report",
                Map.of("snapshotId", snapshotId, "diagnosis", wrongSnapshotReport(snapshotId))));
        assertEquals(Boolean.TRUE, failure.isError(), "Ein fremder Snapshotbezug muss abgewiesen werden.");
    }

    @Test
    @DisplayName("Fachlicher Toolfehler liefert isError mit strukturiertem Fehler")
    void unknownTargetProducesStructuredError() {
        McpSchema.CallToolResult result = client.callTool(callToolRequest("ops_collect_snapshot", Map.of("targetId", "TGT-unbekannt")));
        assertEquals(Boolean.TRUE, result.isError());
        assertNotNull(result.structuredContent());
        Map<?, ?> payload = (Map<?, ?>) result.structuredContent();
        Map<?, ?> error = (Map<?, ?>) payload.get("error");
        assertNotNull(error, "Der Fehler muss strukturiert sein: " + payload);
        assertEquals("TARGET_EXPIRED", error.get("code"));
    }

    @Test
    @DisplayName("Ein ungueltiges Argument wird abgewiesen, ohne eine Quelle aufzurufen")
    void invalidArgumentsAreRejected() {
        McpSchema.CallToolResult result = client.callTool(callToolRequest("ops_read_evidence", Map.of("snapshotId", "nicht-gueltig", "evidenceId", "EV-0001")));
        assertEquals(Boolean.TRUE, result.isError());
    }

    /** Vermeidet die in der gepinnten SDK-Version als deprecated markierten Konstruktoren. */
    private static McpSchema.CallToolRequest callToolRequest(String tool, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(tool).arguments(arguments).build();
    }

    private Map<String, Object> call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(callToolRequest(tool, arguments));
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "Werkzeug " + tool + " meldete einen Fehler: " + result.content());
        assertNotNull(result.structuredContent(), "strukturedContent fehlt bei " + tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.structuredContent();
        return payload;
    }

    private static boolean expectedReadOnly(String tool) {
        return switch (tool) {
            case "ops_collect_snapshot", "ops_store_report" -> false;
            default -> true;
        };
    }

    private static Map<String, Object> insufficientEvidenceReport(String snapshotId) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("snapshotId", snapshotId);
        document.put("language", "de-CH");
        document.put("assessment", "insufficient_evidence");
        document.put("title", "Protokolltest ohne fachliche Bewertung");
        document.put("summary", "Dieser Bericht prueft ausschliesslich den MCP-Vertrag und behauptet keine Ursache.");
        document.put("facts", List.of());
        document.put("hypotheses", List.of());
        document.put("timeline", List.of());
        document.put("recommendations", List.of());
        document.put("openQuestions", List.of("Fachliche Bewertung nicht Teil dieses Tests."));
        document.put("limitations", List.of("Protokolltest; keine Aussage ueber die Ursache."));
        document.put("actionsTaken", List.of());
        return document;
    }

    private static Map<String, Object> wrongSnapshotReport(String snapshotId) {
        Map<String, Object> document = insufficientEvidenceReport(snapshotId);
        document.put("snapshotId", "20260101T000000Z-aaaaaaaa");
        return document;
    }

    private static String fixtureConfig(Path scenarioRoot, Path storageRoot) {
        return """
                schemaVersion: 1
                mode: fixture

                storage:
                  root: "%s"

                security:
                  allowPersonalCredentials: false
                  modelDataPolicy: internal-approved-only
                  identityMode: restricted

                limits:
                  collectTimeoutSeconds: 90
                  requestTimeoutSeconds: 15
                  connectTimeoutSeconds: 5
                  maxParallelRequests: 3
                  maxRequests: 60
                  maxPods: 8
                  maxEvents: 500
                  maxGitFiles: 12
                  maxBuildLogBytes: 8388608
                  maxContainerLogBytes: 1048576
                  maxTotalInputBytes: 33554432
                  maxEvidenceResponseBytes: 32768

                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    ocExecutable: "/bin/false"
                    kubeconfig: "/dev/null"
                    context: "fixture-context"
                    identityMode: restricted
                    directHosts:
                      - fixture.invalid
                    allowedNamespaces:
                      - fixture-integration
                      - fixture-service-b-integration

                jenkins:
                  apps-deploy:
                    baseUrl: "https://jenkins.fixture.invalid"
                    username: fixture-reader
                    token:
                      environmentVar: OPS_AGENT_FIXTURE_TOKEN
                    tls:
                      additionalCaFile: null
                    proxy:
                      mode: direct
                    allowedJobPaths:
                      - ["fixture-folder", "modelfinder"]
                      - ["fixture-folder", "service-b"]
                    allowedParameterNames:
                      - TARGET_NAMESPACE
                      - IMAGE_TAG

                repositories:
                  deployment-config:
                    repositoryId: sogis/openshift-templates
                    localPath: "%s"
                    allowedFiles:
                      - templates/modelfinder.yaml
                      - templates/service-b.yaml
                      - env/integration.env
                      - env/service-b-integration.env

                applications:
                  modelfinder:
                    displayName: Modelfinder
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
                          parameterFiles:
                            - env/integration.env
                          allowedParameterNames:
                            - INITIAL_DELAY_SECONDS
                            - FAILURE_THRESHOLD
                          revision:
                            source: jenkins-scm
                            repositoryId: sogis/openshift-templates
                  fixture-service-b:
                    displayName: Fixture Service B
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: fixture-service-b-integration
                        workload:
                          kind: Deployment
                          name: fixture-service-b
                          containerNames: [service-b]
                        deployment:
                          jenkins: apps-deploy
                          jobPath: ["fixture-folder", "service-b"]
                        configuration:
                          repository: deployment-config
                          templateFiles:
                            - templates/service-b.yaml
                          parameterFiles:
                            - env/service-b-integration.env
                          allowedParameterNames:
                            - INITIAL_DELAY_SECONDS
                          revision:
                            source: jenkins-scm
                            repositoryId: sogis/openshift-templates

                fixture:
                  root: "%s"
                  scenario: t01-liveness-startup
                  clock: fixed
                  fixedNow: "2026-10-01T10:10:00Z"
                """.formatted(storageRoot, scenarioRoot, scenarioRoot);
    }

    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }
}
