package ch.so.agi.ops.cli;

import ch.so.agi.ops.application.ApplicationCatalog;
import ch.so.agi.ops.application.ClockProvider;
import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.application.DoctorService;
import ch.so.agi.ops.application.InvestigationService;
import ch.so.agi.ops.application.TargetBuilder;
import ch.so.agi.ops.application.TargetRegistry;
import ch.so.agi.ops.application.TargetResolutionException;
import ch.so.agi.ops.adapter.ServiceFactory;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.application.port.SnapshotStore;
import ch.so.agi.ops.config.ConfigException;
import ch.so.agi.ops.config.ConfigLoader;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.evidence.FileSnapshotStore;
import ch.so.agi.ops.mcp.EvidenceReadBudget;
import ch.so.agi.ops.mcp.McpStdioServer;
import ch.so.agi.ops.mcp.OpsTools;
import ch.so.agi.ops.report.DiagnosisDocument;
import ch.so.agi.ops.report.DiagnosisParser;
import ch.so.agi.ops.report.FileReportStore;
import ch.so.agi.ops.report.ReportException;
import ch.so.agi.ops.report.ReportStore;
import ch.so.agi.ops.report.StoredReport;
import ch.so.agi.ops.security.PathSafety;
import ch.so.agi.ops.security.ScopeViolationException;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import ch.so.agi.ops.util.VersionInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * CLI-Ausfuehrung. CLI und MCP verwenden dieselben Application Services.
 *
 * <p>stdout traegt das Ergebnis, stderr traegt Logs. Es gibt keine Befehle zum Aendern,
 * Reparieren, Ausrollen oder Ausloesen.</p>
 */
public final class CliRunner {

    private final PrintStream out;
    private final PrintStream err;
    private final String applicationVersion;

    public CliRunner(PrintStream out, PrintStream err, String applicationVersion) {
        this.out = out;
        this.err = err;
        this.applicationVersion = applicationVersion;
    }

    /** Fuehrt einen Befehl aus und liefert den Exitcode. */
    public int run(String[] args) {
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (CliUsageException ex) {
            err.println("Fehler: " + ex.getMessage());
            err.println(CliArguments.USAGE);
            return ExitCodes.INVALID_INPUT_OR_CONFIG;
        }
        if (arguments.flag("help") || arguments.positional().isEmpty()
                || "help".equals(arguments.positional().get(0))) {
            out.println(CliArguments.USAGE);
            return ExitCodes.OK;
        }
        String command = arguments.command();
        if (!List.of("doctor", "applications", "resolve", "collect", "inspect", "report", "serve")
                .contains(command)) {
            err.println("Fehler: unbekannter Befehl '" + PathSafety.describe(command) + "'.");
            err.println(CliArguments.USAGE);
            return ExitCodes.INVALID_INPUT_OR_CONFIG;
        }
        boolean json = arguments.flag("json");
        if (json && "serve".equals(command)) {
            err.println("Fehler: --json ist bei serve nicht zulaessig; stdout bleibt JSON-RPC.");
            return ExitCodes.INVALID_INPUT_OR_CONFIG;
        }
        try {
            OpsConfig config = new ConfigLoader().load(resolveConfigPath(arguments.option("config")));
            return execute(command, arguments, config, json);
        } catch (ConfigException ex) {
            return reportConfigError(ex, json);
        } catch (ScopeViolationException ex) {
            return fail(json, ExitCodes.SCOPE_VIOLATION, ex.code(), ex.getMessage());
        } catch (ArtifactIntegrityException ex) {
            return fail(json, exitCodeFor(ex.code()), ex.code(), ex.getMessage());
        } catch (TargetResolutionException ex) {
            return fail(json, exitCodeFor(ex.code()), ex.code(), ex.getMessage());
        } catch (CliUsageException ex) {
            err.println("Fehler: " + ex.getMessage());
            err.println(CliArguments.USAGE);
            return ExitCodes.INVALID_INPUT_OR_CONFIG;
        } catch (ReportException ex) {
            return fail(json, ExitCodes.REPORT_INVALID, ex.code(), ex.getMessage());
        } catch (OpsTools.OpsToolException ex) {
            return fail(json, exitCodeFor(ex.code()), ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            String message = ch.so.agi.ops.security.Redactor.redact(String.valueOf(ex.getMessage())).text();
            err.println("Interner Fehler: " + message);
            return fail(json, ExitCodes.SOURCE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                    message == null || message.isBlank() ? "Unerwarteter interner Fehler." : message);
        }
    }

    private int execute(String command, CliArguments arguments, OpsConfig config, boolean json) {
        JsonMapper mapper = ch.so.agi.ops.config.YamlSupport.jsonMapper();
        ClockProvider clock = clockFor(config);
        RandomGenerator random = new java.util.Random(20261012L);
        VersionInfo versionInfo = new VersionInfo(applicationVersion)
                .put("dataMode", config.mode().wireName())
                .put("limits", "collect=" + config.limits().collectTimeoutSeconds() + "s");
        if (config.fixture() != null && config.fixture().scenario() != null) {
            versionInfo.put("fixtureScenario", config.fixture().scenario());
        }

        SnapshotStore snapshotStore = new FileSnapshotStore(config.storageRoot(), clock.clock(), random, mapper);
        ReportStore reportStore = new FileReportStore(config.storageRoot(), clock.clock(), random);
        ApplicationCatalog catalog = new ApplicationCatalog(config);
        TargetBuilder targetBuilder = new TargetBuilder(config, clock);
        TargetRegistry registry = new TargetRegistry(clock);
        ServiceFactory serviceFactory = ServiceFactory.forConfig(config, clock.clock(), mapper, versionInfo);
        Function<String, BuildReader> buildReaders = serviceFactory::buildReader;
        ClusterReader clusterReader = serviceFactory.clusterReader();
        ConfigurationReader configurationReader = serviceFactory.configurationReader();

        InvestigationService investigationService = new InvestigationService(config, clock, snapshotStore,
                clusterReader, buildReaders, configurationReader, mapper, versionInfo);

        OpsTools tools = new OpsTools(config, clock, catalog, targetBuilder, registry, investigationService,
                snapshotStore, reportStore,
                new EvidenceReadBudget(config.limits().maxEvidenceResponseBytes()), buildReaders, mapper,
                config.fixture() == null ? null : config.fixture().scenario());

        return switch (command) {
            case "doctor" -> runDoctor(config, serviceFactory, json);
            case "applications" -> runApplications(catalog, arguments, json);
            case "resolve" -> runResolve(tools, arguments, json);
            case "collect" -> runCollect(tools, arguments, config, json);
            case "inspect" -> runInspect(tools, arguments, json);
            case "report" -> runReport(tools, snapshotStore, arguments, config, json);
            case "serve" -> runServe(tools, config, mapper);
            default -> ExitCodes.INVALID_INPUT_OR_CONFIG;
        };
    }

    private int runDoctor(OpsConfig config, ServiceFactory serviceFactory, boolean json) {
        DoctorReader reader = serviceFactory.doctorReader();
        DoctorService doctorService = new DoctorService(config, reader, applicationVersion);
        DoctorService.DoctorReport report = doctorService.run();
        print(json, report.toMap(), this::renderDoctor);
        return report.hasFailures() ? ExitCodes.SOURCE_UNAVAILABLE : ExitCodes.OK;
    }

    private int runApplications(ApplicationCatalog catalog, CliArguments arguments, boolean json) {
        String applicationId = arguments.option("application");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataMode", catalog.list().isEmpty() ? "unknown" : null);
        List<Map<String, Object>> applications = new ArrayList<>();
        if (applicationId == null) {
            catalog.list().forEach(view -> applications.add(view.toMap()));
        } else {
            var view = catalog.find(applicationId);
            if (view == null) {
                throw new CliUsageException("Unbekannte Anwendung '" + PathSafety.describe(applicationId)
                        + "'. Bekannt sind: " + String.join(", ", catalog.applicationIds()));
            }
            applications.add(view.toMap());
        }
        result.remove("dataMode");
        result.put("applications", applications);
        print(json, result, this::renderApplications);
        return ExitCodes.OK;
    }

    private int runResolve(OpsTools tools, CliArguments arguments, boolean json) {
        Map<String, Object> toolArguments = new LinkedHashMap<>();
        toolArguments.put("applicationId", arguments.requireOption("application"));
        toolArguments.put("environment", arguments.requireOption("environment"));
        toolArguments.put("build", buildSelector(arguments));
        Map<String, Object> result = tools.resolveTarget(toolArguments);
        print(json, result, map -> renderKeyValues("Ziel aufgeloest", map));
        return ExitCodes.OK;
    }

    private int runCollect(OpsTools tools, CliArguments arguments, OpsConfig config, boolean json) {
        if (arguments.option("scenario") != null && !config.isFixture()) {
            throw new CliUsageException("--scenario ist nur im Fixturemodus zulaessig.");
        }
        Map<String, Object> result;
        if (arguments.option("scenario") != null) {
            // Szenariowechsel geschieht ueber die Fixture-Konfiguration; die CLI meldet nur,
            // dass das Standardszenario verwendet wird, wenn kein Szenario verdrahtet ist.
            err.println("[ops-agent] Hinweis: --scenario ist gesetzt; im Fixturebetrieb wird das Szenario "
                    + "aus der Konfiguration bzw. dem Fixture-Reader verwendet.");
        }
        Map<String, Object> toolArguments = new LinkedHashMap<>();
        toolArguments.put("applicationId", arguments.requireOption("application"));
        toolArguments.put("environment", arguments.requireOption("environment"));
        toolArguments.put("build", buildSelector(arguments));
        Map<String, Object> target = tools.resolveTarget(toolArguments);
        result = tools.collectSnapshot(Map.of("targetId", String.valueOf(target.get("targetId"))));
        print(json, result, map -> renderKeyValues("Snapshot gesammelt", map));
        String status = String.valueOf(result.get("collectionStatus"));
        if ("failed".equals(status)) {
            return ExitCodes.SOURCE_UNAVAILABLE;
        }
        if ("partial".equals(status)) {
            return ExitCodes.PARTIAL_SNAPSHOT;
        }
        return ExitCodes.OK;
    }

    private int runInspect(OpsTools tools, CliArguments arguments, boolean json) {
        Map<String, Object> result = tools.getSnapshot(Map.of("snapshotId", arguments.requireOption("snapshot")));
        print(json, result, map -> renderKeyValues("Snapshot", map));
        return ExitCodes.OK;
    }

    private int runReport(OpsTools tools, SnapshotStore snapshotStore, CliArguments arguments, OpsConfig config,
            boolean json) {
        String snapshotId = arguments.requireOption("snapshot");
        Path input = Path.of(arguments.requireOption("input")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new CliUsageException("Die Eingabedatei wurde nicht gefunden: " + input.getFileName());
        }
        byte[] bytes;
        try {
            long size = Files.size(input);
            if (size > config.limits().maxReportDraftBytes()) {
                throw new CliUsageException("Der Berichtsentwurf ist groesser als das konfigurierte Budget von "
                        + config.limits().maxReportDraftBytes() + " Bytes.");
            }
            bytes = Files.readAllBytes(input);
        } catch (IOException ex) {
            throw new CliUsageException("Die Eingabedatei ist nicht lesbar.");
        }
        DiagnosisDocument document = DiagnosisParser.parse(bytes, config.limits().maxReportDraftBytes());
        StoredSnapshot snapshot;
        try {
            snapshot = snapshotStore.read(SnapshotId.of(snapshotId));
        } catch (IllegalArgumentException ex) {
            throw new CliUsageException("Die Snapshot-ID hat ein unzulaessiges Format.");
        }
        Map<String, Object> diagnosis = toMap(ch.so.agi.ops.config.YamlSupport.jsonMapper()
                .valueToTree(document.toMap()));
        Map<String, Object> result = tools.storeReport(Map.of("snapshotId", snapshotId, "diagnosis", diagnosis));
        print(json, result, map -> renderKeyValues("Bericht gespeichert", map));
        return Boolean.TRUE.equals(result.get("valid")) ? ExitCodes.OK : ExitCodes.REPORT_INVALID;
    }

    private int runServe(OpsTools tools, OpsConfig config, JsonMapper mapper) {
        McpStdioServer server = new McpStdioServer(tools, config, mapper, err);
        server.serve(System.in, new java.io.FileOutputStream(java.io.FileDescriptor.out));
        return ExitCodes.OK;
    }

    private Map<String, Object> buildSelector(CliArguments arguments) {
        boolean lastFailed = arguments.flag("last-failed");
        String build = arguments.option("build");
        if (lastFailed && build != null) {
            throw new CliUsageException("--build und --last-failed duerfen nicht gemeinsam angegeben werden.");
        }
        if (!lastFailed && build == null) {
            throw new CliUsageException("Entweder --build <nummer> oder --last-failed ist erforderlich.");
        }
        Map<String, Object> selector = new LinkedHashMap<>();
        if (lastFailed) {
            selector.put("kind", "lastFailed");
            return selector;
        }
        int number;
        try {
            number = Integer.parseInt(build);
        } catch (NumberFormatException ex) {
            throw new CliUsageException("--build muss eine positive Ganzzahl sein.");
        }
        if (number <= 0) {
            throw new CliUsageException("--build muss eine positive Ganzzahl sein.");
        }
        selector.put("kind", "number");
        selector.put("number", number);
        return selector;
    }

    private ClockProvider clockFor(OpsConfig config) {
        if (config.fixture() != null && config.fixture().isFixedClock() && config.fixture().fixedNow() != null) {
            return ClockProvider.fixed(java.time.Instant.parse(config.fixture().fixedNow()));
        }
        return ClockProvider.system();
    }

    private Path resolveConfigPath(String value) {
        if (value == null || value.isBlank()) {
            throw new CliUsageException("Option --config ist erforderlich.");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private int reportConfigError(ConfigException ex, boolean json) {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "error");
            payload.put("code", ex.code().name());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", ex.getMessage());
            List<Map<String, Object>> problems = new ArrayList<>();
            ex.problems().forEach(problem -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", problem.code().name());
                item.put("field", problem.sourceId());
                item.put("message", problem.message());
                problems.add(item);
            });
            error.put("problems", problems);
            payload.put("error", error);
            out.println(toJson(payload));
        } else {
            err.println("Konfigurationsfehler (" + ex.code().name() + "): " + ex.getMessage());
            ex.problems().forEach(problem ->
                    err.println("  - " + problem.sourceId() + ": " + problem.message()));
        }
        return ExitCodes.INVALID_INPUT_OR_CONFIG;
    }

    private int fail(boolean json, int exitCode, ErrorCode code, String message) {
        String redacted = ch.so.agi.ops.security.Redactor.redact(message == null ? "" : message).text();
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "error");
            payload.put("code", code.name());
            payload.put("message", redacted);
            out.println(toJson(payload));
        } else {
            err.println("Fehler (" + code.name() + "): " + redacted);
        }
        return exitCode;
    }

    private static int exitCodeFor(ErrorCode code) {
        return switch (code) {
            case CONFIG_INVALID, CONFIG_INCOMPLETE, INVALID_INPUT, INPUT_TOO_LONG, TARGET_AMBIGUOUS,
                    TARGET_EXPIRED -> ExitCodes.INVALID_INPUT_OR_CONFIG;
            case REPORT_INVALID -> ExitCodes.REPORT_INVALID;
            case SCOPE_DENIED, FORBIDDEN -> ExitCodes.SCOPE_VIOLATION;
            case BUDGET_EXCEEDED, DATA_TRUNCATED -> ExitCodes.PARTIAL_SNAPSHOT;
            default -> ExitCodes.SOURCE_UNAVAILABLE;
        };
    }

    private void print(boolean json, Map<String, Object> payload, java.util.function.Consumer<Map<String, Object>> renderer) {
        if (json) {
            out.println(toJson(payload));
        } else {
            renderer.accept(payload);
        }
    }

    private String toJson(Map<String, Object> payload) {
        return ch.so.agi.ops.config.YamlSupport.jsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.properties().forEach(entry -> result.put(entry.getKey(), convert(entry.getValue())));
        return result;
    }

    private static Object convert(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(convert(element)));
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asString();
    }

    private void renderDoctor(Map<String, Object> payload) {
        out.println("doctor – Modus " + payload.get("dataMode"));
        Object capabilities = payload.get("capabilities");
        if (capabilities instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value instanceof Map<?, ?> entry) {
                    out.println(String.format("  %-24s %-14s %s", key, entry.get("status"), entry.get("detail")));
                    Object next = entry.get("nextStep");
                    if (next != null && !String.valueOf(next).isBlank()) {
                        out.println(String.format("  %-24s %-14s %s", "", "->", next));
                    }
                }
            });
        }
        Object notes = payload.get("notes");
        if (notes instanceof List<?> list) {
            list.forEach(note -> out.println("  Hinweis: " + note));
        }
    }

    @SuppressWarnings("unchecked")
    private void renderApplications(Map<String, Object> payload) {
        Object applications = payload.get("applications");
        if (!(applications instanceof List<?> list) || list.isEmpty()) {
            out.println("Keine Anwendungen konfiguriert.");
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> application)) {
                continue;
            }
            out.println(application.get("applicationId") + " (" + application.get("displayName") + ")");
            Object environments = application.get("environments");
            if (environments instanceof List<?> envList) {
                for (Object envItem : envList) {
                    if (envItem instanceof Map<?, ?> environment) {
                        out.println(String.format("  %-16s %-10s %-28s %s", environment.get("environment"),
                                environment.get("configurationStatus"),
                                String.valueOf(environment.get("namespace")),
                                String.valueOf(environment.get("workloadName"))));
                        Object missing = environment.get("missingFields");
                        if (missing instanceof List<?> missingList && !missingList.isEmpty()) {
                            out.println("      fehlend: " + String.join(", ", missingList.stream()
                                    .map(String::valueOf).toList()));
                        }
                    }
                }
            }
        }
    }

    private void renderKeyValues(String title, Map<String, Object> payload) {
        out.println(title);
        payload.forEach((key, value) -> {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                out.println("  " + key + ":");
                renderNested(value, "    ");
            } else if (value != null) {
                out.println("  " + key + ": " + value);
            }
        });
    }

    private void renderNested(Object value, String indent) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    out.println(indent + key + ":");
                    renderNested(item, indent + "  ");
                } else if (item != null) {
                    out.println(indent + key + ": " + item);
                }
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    renderNested(item, indent + "  ");
                } else if (item != null) {
                    out.println(indent + "- " + item);
                }
            }
        }
    }

    /** Zaehlt die Quellen, die eine technische Luecke melden; nur fuer die Standardausgabe. */
    static int gapCount(List<ch.so.agi.ops.snapshot.SourceSummary> sources) {
        return (int) sources.stream().filter(source -> source.status().isGap()).count();
    }

    /** Status, den ein Teilresultat fuer die CLI-Kommunikation erzeugt. */
    static String describeStatus(SourceStatus status) {
        return status.name();
    }

    /** Aufloesung eines Builds ohne Ziel (nur fuer Tests und Doctor). */
    static BuildRef buildRef(String instanceId, List<String> jobPath, int number) {
        return new BuildRef(instanceId, jobPath, number);
    }

    /** Selector fuer lastFailed; nur fuer Doctor und Tests. */
    static BuildSelector lastFailedSelector(List<String> jobPath) {
        return new BuildSelector(BuildSelector.Kind.LAST_FAILED, null, jobPath, null);
    }

    /** Lesen eines Snapshots ohne MCP-Werkzeug; wird von inspect verwendet. */
    static StoredSnapshot readSnapshot(SnapshotStore store, String snapshotId) {
        return store.read(SnapshotId.of(snapshotId));
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String redact(String value) {
        return ch.so.agi.ops.security.Redactor.redact(value).text();
    }
}
