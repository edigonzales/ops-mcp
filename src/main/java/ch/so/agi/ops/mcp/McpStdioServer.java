package ch.so.agi.ops.mcp;

import ch.so.agi.ops.config.OpsConfig;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * stdio-MCP-Server mit genau den sechs definierten V1-Werkzeugen.
 *
 * <p>stdout bleibt ausschliesslich JSON-RPC. Alle Logausgaben gehen nach stderr und werden
 * redigiert. Es gibt keinen eigenen LLM-Aufruf und keine providerabhaengige Agentenschleife.</p>
 *
 * <p>Die im Client konfigurierte Tool-Allowlist und die Zeitlimits sind Hinweise, keine
 * Zugriffskontrolle: Die technische Grenze liegt in den festen Operationen dieser Werkzeuge
 * und in den Rechten der technischen Identitaeten.</p>
 */
public final class McpStdioServer {

    public static final String SERVER_NAME = "agi-ops-agent";
    /** Muss zur tatsaechlich getesteten SDK-/Clientkombination passen. */
    public static final String SERVER_VERSION = "1.0.0";
    /** Reserve ueber dem konfigurierten Collect-Timeout; Defaultbeispiel 120 s bei 90 s Budget. */
    public static final Duration REQUEST_TIMEOUT_RESERVE = Duration.ofSeconds(30);

    private final OpsTools tools;
    private final OpsConfig config;
    private final JsonMapper mapper;
    private final java.io.PrintStream errorStream;

    public McpStdioServer(OpsTools tools, OpsConfig config, JsonMapper mapper, java.io.PrintStream errorStream) {
        this.tools = tools;
        this.config = config;
        this.mapper = mapper;
        this.errorStream = errorStream == null ? System.err : errorStream;
    }

    /** Startet den Server auf stdin/stdout und blockiert, bis der Client die Verbindung beendet. */
    public void serve(java.io.InputStream in, java.io.OutputStream out) {
        McpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mcpJsonMapper, in, out);
        McpSyncServer server = build(transport);
        errorStream.println("[ops-agent] " + protocolInformation() + " bereit (stdout bleibt JSON-RPC)");
        try {
            // Der stdio-Transport laeuft in eigenen Threads. Der Hauptthread bleibt aktiv, bis
            // der Client stdin schliesst. Es gibt kein Polling, keinen Timer und keinen Retry.
            waitUntilInterrupted();
        } finally {
            server.closeGracefully();
        }
    }

    private void waitUntilInterrupted() {
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Baut den Server; fuer Tests ohne echten stdio-Transport. */
    public McpSyncServer build(io.modelcontextprotocol.spec.McpServerTransportProvider transportProvider) {
        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
        for (OpsTools.ToolDefinition definition : OpsTools.definitions()) {
            specifications.add(toSpecification(definition));
        }
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions(instructions())
                .requestTimeout(config.limits().collectTimeout().plus(REQUEST_TIMEOUT_RESERVE))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specifications)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification toSpecification(OpsTools.ToolDefinition definition) {
        // Der Builder von McpSchema.Tool ist in der gepinnten SDK-Version als deprecated markiert;
        // der oeffentliche Record-Konstruktor ist der stabile Weg.
        McpSchema.Tool tool = new McpSchema.Tool(
                definition.name(),
                definition.title(),
                definition.description(),
                definition.inputSchema(),
                definition.outputSchema(),
                McpSchema.ToolAnnotations.builder()
                        .title(definition.title())
                        .readOnlyHint(definition.readOnlyHint())
                        .destructiveHint(definition.destructiveHint())
                        .idempotentHint(definition.idempotentHint())
                        .openWorldHint(definition.openWorldHint())
                        .build(),
                null,
                java.util.List.of());
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(definition.name(), request))
                .build();
    }

    private McpSchema.CallToolResult handle(String toolName, McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            Map<String, Object> result = dispatch(toolName, arguments);
            String text = toJson(result);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(text)
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (OpsTools.OpsToolException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", ex.code().name());
            error.put("sourceId", toolName);
            error.put("message", ex.getMessage());
            error.put("retryable", false);
            if (ex.toError(toolName).suggestedAction() != null
                    && !ex.toError(toolName).suggestedAction().isBlank()) {
                error.put("suggestedAction", ex.toError(toolName).suggestedAction());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", error);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toJson(payload))
                    .structuredContent(payload)
                    .isError(true)
                    .build();
        } catch (RuntimeException ex) {
            String message = ch.so.agi.ops.security.Redactor.redact(String.valueOf(ex.getMessage())).text();
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", ch.so.agi.ops.domain.ErrorCode.INTERNAL_ERROR.name());
            error.put("sourceId", toolName);
            error.put("message", message == null || message.isBlank()
                    ? "Unerwarteter interner Fehler."
                    : message);
            error.put("retryable", false);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", error);
            errorStream.println("[ops-agent] interner Fehler in " + toolName + ": " + error.get("message"));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toJson(payload))
                    .structuredContent(payload)
                    .isError(true)
                    .build();
        }
    }

    private Map<String, Object> dispatch(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case ToolSchemas.TOOL_LIST_APPLICATIONS -> tools.listApplications(arguments);
            case ToolSchemas.TOOL_RESOLVE_TARGET -> tools.resolveTarget(arguments);
            case ToolSchemas.TOOL_COLLECT_SNAPSHOT -> tools.collectSnapshot(arguments);
            case ToolSchemas.TOOL_GET_SNAPSHOT -> tools.getSnapshot(arguments);
            case ToolSchemas.TOOL_READ_EVIDENCE -> tools.readEvidence(arguments);
            case ToolSchemas.TOOL_STORE_REPORT -> tools.storeReport(arguments);
            default -> throw new OpsTools.OpsToolException(ch.so.agi.ops.domain.ErrorCode.INVALID_INPUT,
                    "Unbekanntes Werkzeug: " + toolName, "Nur die sechs ops_*-Werkzeuge verwenden.");
        };
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (RuntimeException ex) {
            return "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Antwort nicht serialisierbar\"}}";
        }
    }

    /** Kurze Anweisung an den Client. Die ausfuehrliche Diagnoseanweisung liegt in agents/ops-diagnose.md. */
    private String instructions() {
        return """
                Read-only Betriebsdiagnose fuer OpenShift-Deployments (Amt fuer Geoinformation, Kanton Solothurn).
                Modus: %s. Verwende ausschliesslich die sechs ops_*-Werkzeuge: Ziel aufloesen, Snapshot sammeln,
                Evidence lesen, Bericht speichern. Die untersuchten Systeme werden nur gelesen; es gibt keine
                Schreib-, Start-, Stop- oder Reparaturfunktion. Lies die relevanten Belege ueber ops_read_evidence
                und unterscheide Fakten, Hypothesen und offene Punkte. Erfinde keine Zeitstempel, Jobpfade,
                Namespaces, Endpunkte oder Budgetwerte. Speichere den Bericht mit ops_store_report; der Ablageort
                wird ausschliesslich vom Server bestimmt.
                """.formatted(config.mode().wireName()).trim();
    }

    /** Nur fuer Tests: Ausgabe der Protokollversion, die die SDK-Version verhandelt. */
    public static String protocolInformation() {
        return "server=" + SERVER_NAME + "/" + SERVER_VERSION
                + " sdk=mcp-java-sdk/2.0.1 transport=stdio";
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
