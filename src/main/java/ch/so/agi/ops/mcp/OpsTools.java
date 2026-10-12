package ch.so.agi.ops.mcp;

import ch.so.agi.ops.application.ApplicationCatalog;
import ch.so.agi.ops.application.ClockProvider;
import ch.so.agi.ops.application.InvestigationService;
import ch.so.agi.ops.application.TargetBuilder;
import ch.so.agi.ops.application.TargetRegistry;
import ch.so.agi.ops.application.TargetResolutionException;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.application.port.SnapshotStore;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.report.DiagnosisDocument;
import ch.so.agi.ops.report.DiagnosisParser;
import ch.so.agi.ops.report.ReportException;
import ch.so.agi.ops.report.ReportStore;
import ch.so.agi.ops.report.ReportValidationResult;
import ch.so.agi.ops.report.StoredReport;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fachliche Implementierung der sechs V1-Werkzeuge.
 *
 * <p>Diese Klasse enthaelt die Fachlogik der Werkzeuge und ist unabhaengig vom Transport.
 * Die MCP-Handler rufen ausschliesslich hier auf; CLI und MCP verwenden dieselben Application
 * Services.</p>
 */
public final class OpsTools {

    private final OpsConfig config;
    private final ClockProvider clock;
    private final ApplicationCatalog catalog;
    private final TargetBuilder targetBuilder;
    private final TargetRegistry registry;
    private final InvestigationService investigationService;
    private final SnapshotStore snapshotStore;
    private final ReportStore reportStore;
    private final EvidenceReadBudget readBudget;
    private final Function<String, BuildReader> buildReaders;
    private final JsonMapper mapper;
    private final String defaultScenarioId;

    public OpsTools(OpsConfig config, ClockProvider clock, ApplicationCatalog catalog, TargetBuilder targetBuilder,
            TargetRegistry registry, InvestigationService investigationService, SnapshotStore snapshotStore,
            ReportStore reportStore, EvidenceReadBudget readBudget, Function<String, BuildReader> buildReaders,
            JsonMapper mapper, String defaultScenarioId) {
        this.config = config;
        this.clock = clock;
        this.catalog = catalog;
        this.targetBuilder = targetBuilder;
        this.registry = registry;
        this.investigationService = investigationService;
        this.snapshotStore = snapshotStore;
        this.reportStore = reportStore;
        this.readBudget = readBudget;
        this.buildReaders = buildReaders;
        this.mapper = mapper;
        this.defaultScenarioId = defaultScenarioId;
    }

    /** {@code ops_list_applications} */
    public Map<String, Object> listApplications(Map<String, Object> arguments) {
        String applicationId = stringArg(arguments, "applicationId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataMode", config.mode().wireName());
        List<Map<String, Object>> applications = new ArrayList<>();
        if (applicationId == null) {
            for (ApplicationCatalog.ApplicationView view : catalog.list()) {
                applications.add(view.toMap());
            }
        } else {
            ApplicationCatalog.ApplicationView view = catalog.find(applicationId);
            if (view == null) {
                throw new OpsToolException(ErrorCode.CONFIG_INCOMPLETE,
                        "Unbekannte Anwendung '" + sanitize(applicationId) + "'. Bekannt sind: "
                                + String.join(", ", catalog.applicationIds()) + ".",
                        "Anwendungskennung aus ops_list_applications verwenden.");
            }
            applications.add(view.toMap());
        }
        result.put("applications", applications);
        return result;
    }

    /** {@code ops_resolve_target} */
    public Map<String, Object> resolveTarget(Map<String, Object> arguments) {
        String applicationId = requireString(arguments, "applicationId");
        String environment = requireString(arguments, "environment");
        Map<String, Object> build = mapArg(arguments, "build");
        String buildUrl = stringArg(arguments, "buildUrl");
        String scenarioId = config.isFixture() ? defaultScenarioId : null;
        List<String> warnings = new ArrayList<>();

        String buildReaderId = null;
        var application = config.application(applicationId);
        if (application != null) {
            var definition = application.environment(environment);
            if (definition != null && definition.deployment() != null) {
                buildReaderId = definition.deployment().jenkins();
            }
        }

        BuildRef buildRef;
        if (build == null) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT,
                    "Es wurde keine Buildauswahl uebergeben.",
                    "Entweder {\"kind\":\"number\",\"number\":42} oder {\"kind\":\"lastFailed\"} angeben.");
        }
        String kind = requireString(build, "kind");
        switch (kind) {
            case "number" -> {
                Object numberValue = build.get("number");
                if (!(numberValue instanceof Number number) || number.intValue() <= 0) {
                    throw new OpsToolException(ErrorCode.INVALID_INPUT,
                            "Bei kind=number wird eine positive Buildnummer erwartet.",
                            "Eine konkrete Buildnummer angeben.");
                }
                buildRef = new BuildRef(orDefault(buildReaderId, "jenkins"),
                        jobPathOf(applicationId, environment), number.intValue());
            }
            case "lastFailed" -> {
                BuildReader reader = buildReaders.apply(orDefault(buildReaderId, "jenkins"));
                if (reader == null) {
                    throw new OpsToolException(ErrorCode.CONFIG_INCOMPLETE,
                            "Fuer die konfigurierte Jenkins-Instanz ist kein Reader verfuegbar.",
                            "Die Jenkins-Konfiguration pruefen.");
                }
                ResolvedTarget probe;
                try {
                    probe = targetBuilder.build(applicationId, environment,
                            new BuildRef(orDefault(buildReaderId, "jenkins"),
                                    jobPathOf(applicationId, environment), 1),
                            scenarioId, warnings);
                } catch (TargetResolutionException ex) {
                    throw new OpsToolException(ex.code(), ex.getMessage(),
                            "Die lokale Konfiguration vervollstaendigen.");
                }
                var selection = reader.resolveBuild(probe,
                        new BuildSelector(BuildSelector.Kind.LAST_FAILED, null,
                                probe.definition().deployment().jobPath(), null));
                if (selection.status().isGap() || selection.data() == null) {
                    throw new OpsToolException(selection.errors().isEmpty()
                            ? ErrorCode.NOT_FOUND
                            : selection.errors().get(0).code(),
                            selection.errors().isEmpty()
                                    ? "Kein fehlgeschlagener Build gefunden."
                                    : selection.errors().get(0).message(),
                            "Eine konkrete Buildnummer angeben.");
                }
                buildRef = selection.data().toBuildRef();
                warnings.add("lastFailed wurde auf die konkrete Buildnummer " + buildRef.buildNumber()
                        + " aufgeloest und danach nicht weiterverwendet.");
            }
            default -> throw new OpsToolException(ErrorCode.INVALID_INPUT,
                    "Unbekannte Buildauswahl: " + sanitize(kind),
                    "kind muss 'number' oder 'lastFailed' sein.");
        }

        if (buildUrl != null) {
            warnings.add("Der uebergebene Browserlink wird nicht als Netzwerkziel verwendet; massgeblich ist "
                    + "ausschliesslich die konfigurierte Zuordnung. Der Link wurde gegen die Allowlist geprueft.");
            if (!buildUrlStartsWithAllowedHost(buildUrl)) {
                throw new OpsToolException(ErrorCode.SCOPE_DENIED,
                        "Der uebergebene Buildlink passt nicht zur konfigurierten Jenkins-Instanz.",
                        "Nur Links auf die konfigurierte Jenkins-Instanz verwenden.");
            }
        }

        ResolvedTarget target;
        try {
            target = targetBuilder.build(applicationId, environment, buildRef, scenarioId, warnings);
        } catch (TargetResolutionException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    ex.error() == null ? "Die lokale Konfiguration pruefen." : ex.error().suggestedAction());
        }
        registry.register(target);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetId", target.targetId());
        result.put("applicationId", target.applicationId());
        result.put("environment", target.environment());
        result.put("dataMode", target.dataMode().wireName());
        result.put("buildNumber", target.buildRef().buildNumber());
        result.put("jenkinsInstance", target.buildRef().instanceId());
        result.put("jobPath", target.buildRef().jobPath());
        result.put("namespace", target.namespace());
        result.put("workloadKind", target.definition().workload().kind().kubernetesKind());
        result.put("workloadName", target.definition().workload().name());
        result.put("resolvedAt", target.resolvedAt().toString());
        result.put("expiresAt", target.expiresAt().toString());
        result.put("warnings", warningMaps(target.warnings()));
        return result;
    }

    /** {@code ops_collect_snapshot} */
    public Map<String, Object> collectSnapshot(Map<String, Object> arguments) {
        String targetId = requireString(arguments, "targetId");
        ResolvedTarget target;
        try {
            target = registry.require(targetId);
        } catch (TargetResolutionException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    "Das Ziel mit ops_resolve_target erneut aufloesen.");
        }
        ResolvedTarget withBudget = TargetRegistry.withFreshBudget(target, clock);
        InvestigationService.CollectionOutcome outcome;
        try {
            outcome = investigationService.collect(withBudget);
        } catch (ArtifactIntegrityException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    "Den Ausgabebereich und die Schreibrechte pruefen.");
        }
        readBudget.reset(outcome.snapshotId());
        return outcome.toMap();
    }

    /** {@code ops_get_snapshot} */
    public Map<String, Object> getSnapshot(Map<String, Object> arguments) {
        String snapshotId = requireString(arguments, "snapshotId");
        StoredSnapshot snapshot = load(snapshotId);
        Map<String, Object> map = snapshot.summary().toMap();
        JsonNode normalized = snapshot.normalized();
        map.put("findings", jsonList(normalized.get("findings")));
        map.put("revision", jsonMap(normalized.get("revision")));
        map.put("readBudget", readBudget.describe(snapshotId));
        return map;
    }

    /** {@code ops_read_evidence} */
    public Map<String, Object> readEvidence(Map<String, Object> arguments) {
        String snapshotId = requireString(arguments, "snapshotId");
        String evidenceId = requireString(arguments, "evidenceId");
        int startLine = intArg(arguments, "startLine", 1);
        int maxLines = intArg(arguments, "maxLines", 100);
        if (startLine < 1) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "startLine muss mindestens 1 sein.",
                    "Eine gueltige Startzeile angeben.");
        }
        if (maxLines < 1) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "maxLines muss mindestens 1 sein.",
                    "Eine gueltige Zeilenzahl angeben.");
        }
        int effectiveMax = Math.min(maxLines, ch.so.agi.ops.config.Limits.HARD_MAX_EVIDENCE_LINES);
        boolean cappedByHardLimit = maxLines > ch.so.agi.ops.config.Limits.HARD_MAX_EVIDENCE_LINES;
        if (readBudget.exhausted(snapshotId)) {
            throw new OpsToolException(ErrorCode.BUDGET_EXCEEDED,
                    "Das Lesebudget fuer diesen Snapshot ist aufgebraucht. Ein Agent darf durch wiederholte "
                            + "Aufrufe keinen unbeschraenkten Zugriff auf weitere Quellen eroeffnen.",
                    "Mit dem vorhandenen Evidence-Stand arbeiten oder einen neuen Snapshot sammeln.");
        }
        StoredSnapshot snapshot = load(snapshotId);
        if (!snapshot.hasEvidence(evidenceId)) {
            throw new OpsToolException(ErrorCode.NOT_FOUND,
                    "Unbekannte Evidence-ID '" + sanitize(evidenceId) + "' in diesem Snapshot.",
                    "Eine Evidence-ID aus dem Evidence-Index verwenden.");
        }
        EvidencePage page = snapshotStore.readEvidence(SnapshotId.of(snapshotId), evidenceId, startLine,
                effectiveMax);
        List<String> lines = new ArrayList<>(page.lines());
        int remaining = readBudget.remaining(snapshotId);
        long bytes = lines.stream().mapToLong(line -> line.getBytes(StandardCharsets.UTF_8).length + 1).sum();
        boolean byteBudgetHit = false;
        if (bytes > remaining) {
            List<String> trimmed = new ArrayList<>();
            long used = 0;
            for (String line : lines) {
                long size = line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (used + size > remaining) {
                    byteBudgetHit = true;
                    break;
                }
                trimmed.add(line);
                used += size;
            }
            lines = trimmed;
        }
        EvidencePage limited = new EvidencePage(page.evidenceId(), page.type(), page.sourceId(), lines,
                page.startLine(), lines.isEmpty() ? page.startLine() - 1 : page.startLine() + lines.size() - 1,
                page.totalLineCount(), page.hasMore() || byteBudgetHit,
                lines.isEmpty() ? page.nextStartLine() : page.startLine() + lines.size(), page.truncatedLine(),
                byteBudgetHit, page.notes());
        int responseBytes = limited.render().getBytes(StandardCharsets.UTF_8).length;
        boolean withinBudget = readBudget.consume(snapshotId, responseBytes);

        Map<String, Object> map = new LinkedHashMap<>(limited.toMap());
        map.put("snapshotId", snapshotId);
        map.put("readBudget", readBudget.describe(snapshotId));
        if (!withinBudget) {
            map.put("budgetWarning", "Das auftragsweite Lesebudget ist nach dieser Antwort aufgebraucht.");
        }
        if (cappedByHardLimit) {
            map.put("hardLimitNote", "maxLines wurde auf das Hardlimit von "
                    + ch.so.agi.ops.config.Limits.HARD_MAX_EVIDENCE_LINES + " Zeilen begrenzt.");
        }
        return map;
    }

    /** {@code ops_store_report} */
    public Map<String, Object> storeReport(Map<String, Object> arguments) {
        String snapshotId = requireString(arguments, "snapshotId");
        Map<String, Object> diagnosis = mapArg(arguments, "diagnosis");
        if (diagnosis == null) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "Es wurde kein diagnosis-Objekt uebergeben.",
                    "Einen Bericht nach diagnosis-v1.schema.json uebergeben.");
        }
        StoredSnapshot snapshot = load(snapshotId);
        byte[] json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(diagnosis);
        } catch (RuntimeException ex) {
            throw new OpsToolException(ErrorCode.REPORT_INVALID, "Der Bericht ist nicht serialisierbar.",
                    "Den Bericht als JSON-Objekt uebergeben.");
        }
        if (json.length > config.limits().maxReportDraftBytes()) {
            throw new OpsToolException(ErrorCode.REPORT_INVALID,
                    "Der Berichtsentwurf ist groesser als das konfigurierte Budget von "
                            + config.limits().maxReportDraftBytes() + " Bytes.",
                    "Den Bericht kuerzen.");
        }
        DiagnosisDocument document;
        try {
            document = DiagnosisParser.parse(json, config.limits().maxReportDraftBytes());
        } catch (ReportException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    "Den Bericht gemaess diagnosis-v1.schema.json korrigieren.");
        }
        if (document.snapshotId() != null && !document.snapshotId().equals(snapshotId)) {
            throw new OpsToolException(ErrorCode.REPORT_INVALID,
                    "Der Bericht verweist auf einen anderen Snapshot als den uebergebenen.",
                    "snapshotId im Bericht und im Aufruf muessen uebereinstimmen.");
        }
        StoredReport report;
        try {
            report = reportStore.save(snapshot, document);
        } catch (ReportException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    "Die Belegreferenzen und Pflichtfelder pruefen.");
        }
        Map<String, Object> map = new LinkedHashMap<>(report.toMap());
        map.put("storageNote", "Der Bericht liegt ausschliesslich im lokalen Ausgabebereich unter "
                + "reports/<snapshotId>/<reportId>/.");
        return map;
    }

    /** Metadaten der sechs Werkzeuge fuer die Registrierung. */
    public record ToolDefinition(String name, String title, String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, Boolean readOnlyHint, Boolean destructiveHint,
            Boolean idempotentHint, Boolean openWorldHint) {
    }

    public static List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition(ToolSchemas.TOOL_LIST_APPLICATIONS,
                        "Anwendungen und Umgebungen auflisten",
                        "Listet die konfigurierten Anwendungen und Umgebungen mit configurationStatus. "
                                + "Keine Secrets, keine Credentialpfade und keine vollstaendige Systemkonfiguration. "
                                + "Reiner Lesevorgang ohne Seiteneffekte.",
                        ToolSchemas.listApplicationsInput(), ToolSchemas.listApplicationsOutput(),
                        true, false, true, false),
                new ToolDefinition(ToolSchemas.TOOL_RESOLVE_TARGET,
                        "Untersuchungsziel aufloesen",
                        "Loest Anwendung, Umgebung und Buildserverseitig gegen die lokale Allowlist auf und "
                                + "liefert ein kurzlebiges targetId (30 Minuten, nur in diesem Prozess). "
                                + "Bei lastFailed wird der Job gezielt gelesen und die Auswahl einmal zu einer "
                                + "konkreten Nummer aufgeloest. Keine Betriebsaenderung.",
                        ToolSchemas.resolveTargetInput(), ToolSchemas.resolveTargetOutput(),
                        true, false, false, true),
                new ToolDefinition(ToolSchemas.TOOL_COLLECT_SNAPSHOT,
                        "Snapshot sammeln",
                        "Erfasst einen begrenzten, bereinigten Snapshot zu einem targetId und schreibt ihn "
                                + "ausschliesslich lokal. Die Quellsysteme werden nur gelesen; die Operation ist "
                                + "dennoch nicht seiteneffektfrei, weil lokale Dateien entstehen. Jede Erfassung "
                                + "erzeugt eine neue Snapshot-ID. Nicht idempotent.",
                        ToolSchemas.collectSnapshotInput(), ToolSchemas.collectSnapshotOutput(),
                        false, false, false, true),
                new ToolDefinition(ToolSchemas.TOOL_GET_SNAPSHOT,
                        "Snapshot lesen",
                        "Liefert Zusammenfassung, Linkstaerken, Quellenstatus, normalisierte Befunde und den "
                                + "Evidence-Index eines vorhandenen Snapshots. Rein lokales Lesen, keine neuen "
                                + "Systemabfragen.",
                        ToolSchemas.getSnapshotInput(), ToolSchemas.getSnapshotOutput(),
                        true, false, true, false),
                new ToolDefinition(ToolSchemas.TOOL_READ_EVIDENCE,
                        "Evidence lesen",
                        "Liefert einen begrenzten, redigierten, zeilennummerierten Ausschnitt einer "
                                + "snapshotgebundenen Evidence. Maximal 200 Zeilen und das konfigurierte Bytebudget "
                                + "je Antwort; zusaetzlich gilt ein auftragsweites Lesebudget. Kein frei waehlbarer "
                                + "Pfad und keine URL.",
                        ToolSchemas.readEvidenceInput(), ToolSchemas.readEvidenceOutput(),
                        true, false, true, false),
                new ToolDefinition(ToolSchemas.TOOL_STORE_REPORT,
                        "Diagnosebericht speichern",
                        "Validiert einen strukturierten Diagnosebericht gegen Schema, Snapshotbindung und "
                                + "Belegreferenzen und speichert ihn ausschliesslich unter "
                                + "reports/<snapshotId>/<reportId>/. Die Validierung bestaetigt nur Struktur und "
                                + "Referenzexistenz, nicht die Wahrheit der Aussagen.",
                        ToolSchemas.storeReportInput(), ToolSchemas.storeReportOutput(),
                        false, false, false, false));
    }

    private StoredSnapshot load(String snapshotId) {
        SnapshotId id;
        try {
            id = SnapshotId.of(snapshotId);
        } catch (IllegalArgumentException ex) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT,
                    "Die Snapshot-ID hat ein unzulaessiges Format.",
                    "Eine Snapshot-ID aus ops_collect_snapshot verwenden.");
        }
        try {
            return snapshotStore.read(id);
        } catch (ArtifactIntegrityException ex) {
            throw new OpsToolException(ex.code(), ex.getMessage(),
                    "Einen neuen Snapshot sammeln.");
        }
    }

    private boolean buildUrlStartsWithAllowedHost(String buildUrl) {
        try {
            java.net.URI uri = java.net.URI.create(buildUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            return config.jenkins().values().stream()
                    .anyMatch(jenkins -> uri.getHost().equalsIgnoreCase(jenkins.host())
                            && jenkins.baseUrl() != null
                            && buildUrl.startsWith(jenkins.baseUrl()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private List<String> jobPathOf(String applicationId, String environment) {
        var application = config.application(applicationId);
        if (application == null) {
            return List.of();
        }
        var definition = application.environment(environment);
        if (definition == null || definition.deployment() == null) {
            return List.of();
        }
        return definition.deployment().jobPath();
    }

    private static List<Map<String, Object>> warningMaps(List<String> warnings) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String warning : warnings) {
            result.add(Map.of("message", warning));
        }
        return result;
    }

    private static List<Object> jsonList(JsonNode node) {
        List<Object> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> result.add(convert(element)));
        }
        return result;
    }

    private static Map<String, Object> jsonMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), convert(entry.getValue())));
        return result;
    }

    private static Object convert(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonMap(node);
        }
        if (node.isArray()) {
            return jsonList(node);
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

    private static String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "Argument '" + name + "' muss Text sein.",
                    "Die Argumente gemaess Toolschema uebergeben.");
        }
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > 512) {
            throw new OpsToolException(ErrorCode.INPUT_TOO_LONG,
                    "Argument '" + name + "' ist zu lang.", "Ein kuerzeres Argument uebergeben.");
        }
        return text;
    }

    private static String requireString(Map<String, Object> arguments, String name) {
        String value = stringArg(arguments, name);
        if (value == null) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "Argument '" + name + "' fehlt.",
                    "Die Argumente gemaess Toolschema uebergeben.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new OpsToolException(ErrorCode.INVALID_INPUT, "Argument '" + name + "' muss ein Objekt sein.",
                    "Die Argumente gemaess Toolschema uebergeben.");
        }
        return (Map<String, Object>) map;
    }

    private static int intArg(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new OpsToolException(ErrorCode.INVALID_INPUT, "Argument '" + name + "' muss eine Ganzzahl sein.",
                "Die Argumente gemaess Toolschema uebergeben.");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String sanitize(String value) {
        return ch.so.agi.ops.security.PathSafety.describe(value);
    }

    /** Fachlicher Toolfehler; wird als {@code isError: true} mit strukturiertem Fehler geliefert. */
    public static class OpsToolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final ErrorCode code;
        private final String suggestedAction;

        public OpsToolException(ErrorCode code, String message, String suggestedAction) {
            super(message);
            this.code = code;
            this.suggestedAction = suggestedAction;
        }

        public ErrorCode code() {
            return code;
        }

        public OpsError toError(String sourceId) {
            return new OpsError(code, sourceId == null ? "ops-agent" : sourceId, getMessage(), false,
                    suggestedAction == null ? "" : suggestedAction);
        }
    }

    /** Nur fuer Tests: Beschreibung der Verdrahtung. */
    Map<String, Object> wiring() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataMode", config.mode().wireName());
        map.put("storageRoot", config.storageRoot().toString());
        map.put("applications", catalog.applicationIds());
        map.put("jenkinsInstances", List.copyOf(config.jenkins().keySet()));
        map.put("targets", registry.size());
        map.put("evidenceReadLimitBytes", readBudget.perSnapshotLimit());
        map.put("reportStore", reportStore.getClass().getSimpleName());
        map.put("validationOnly", ReportValidationResult.class.getSimpleName());
        return map;
    }
}
