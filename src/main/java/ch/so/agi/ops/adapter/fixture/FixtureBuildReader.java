package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.adapter.jenkins.JenkinsJsonParser;
import ch.so.agi.ops.adapter.jenkins.JenkinsParseException;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import ch.so.agi.ops.security.Redactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fixtureadapter fuer die Jenkins-Quelle.
 *
 * <p>Gelesen werden ausschliesslich die im Szenario hinterlegten Rohantworten:
 * {@code jenkins/job.json}, {@code jenkins/build-<nr>-metadata.json} und
 * {@code jenkins/build-<nr>-console.txt}. Die Metadaten gehen durch <em>denselben</em> Parser
 * wie im Livebetrieb ({@link ch.so.agi.ops.adapter.jenkins.JenkinsJsonParser}).</p>
 *
 * <p>Fehlervarianten sind explizit: liegt {@code jenkins/error.json} mit einem HTTP-Status
 * vor, wird ein erklaerter Fehler ({@code FORBIDDEN}, {@code AUTH_REQUIRED},
 * {@code PROXY_AUTH_REQUIRED}, {@code NOT_FOUND}) geliefert und <em>kein</em> BuildSnapshot.
 * Es gibt keinen stillen Fixture-Fallback.</p>
 */
final class FixtureBuildReader implements BuildReader {

    /** Marker, die als Fehleranker im Console-Log erkannt werden. */
    private static final List<ErrorMarker> ERROR_MARKERS = List.of(
            new ErrorMarker("ERROR", Pattern.compile("(?i)\\bERROR\\b")),
            new ErrorMarker("Failure", Pattern.compile("(?i)\\bFAILURE\\b")),
            new ErrorMarker("Timeout", Pattern.compile("(?i)\\bTIMEOUT\\b")),
            new ErrorMarker("Exception", Pattern.compile("(?i)\\bEXCEPTION\\b")),
            new ErrorMarker("error:", Pattern.compile("(?i)^\\s*error:")));

    private record ErrorMarker(String label, Pattern pattern) {
    }

    /** Jenkins-Zeitstempel wie {@code [2026-10-01T09:58:20.123Z]} am Zeilenanfang. */
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    private static final String ERROR_FILE = "jenkins/error.json";

    private final OpsConfig config;
    private final FixtureScenarios fixtures;
    private final JsonMapper mapper;
    private final Clock clock;
    private final String instanceId;
    private final JenkinsJsonParser parser;

    FixtureBuildReader(OpsConfig config, FixtureScenarios fixtures, JsonMapper mapper, Clock clock,
            String instanceId) {
        this.config = config;
        this.fixtures = fixtures;
        this.mapper = mapper;
        this.clock = clock;
        this.instanceId = instanceId;
        this.parser = JenkinsJsonParserFactory.create(mapper);
    }

    @Override
    public String sourceId() {
        return instanceId;
    }

    @Override
    public SourceResult<BuildSelection> resolveBuild(ResolvedTarget target, BuildSelector selector) {
        Instant collectedAt = clock.instant();
        FixtureData scenario;
        try {
            scenario = fixtures.forTarget(target);
        } catch (RuntimeException ex) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, selection(target, 0, null, null),
                    collectedAt, List.of(), List.of(OpsError.of(ErrorCode.CONFIG_INCOMPLETE, instanceId,
                            "Das Fixture-Szenario ist nicht aufloesbar.")));
        }
        OpsError transport = transportError(scenario);
        if (transport != null) {
            BuildSelector.Kind kind = selector == null ? BuildSelector.Kind.NUMBER : selector.kind();
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE,
                    selection(target, selector == null || selector.number() == null ? 0 : selector.number(),
                            null, null),
                    collectedAt, List.of("Jenkins-Rohantwort nicht lesbar; die Auswahl bleibt unaufgeloest."),
                    List.of(transport));
        }

        BuildSelector.Kind kind = selector == null ? BuildSelector.Kind.NUMBER : selector.kind();
        if (kind == BuildSelector.Kind.NUMBER && selector.number() != null) {
            int number = selector.number();
            if (!fixtures.exists(scenario, buildMetadataFile(number))) {
                return SourceResult.of(instanceId, SourceStatus.NOT_PRESENT, selection(target, number, null, null),
                        collectedAt, List.of(), List.of(new OpsError(ErrorCode.NOT_FOUND, instanceId,
                                "Im Szenario fehlt die Buildmetadaten-Datei " + buildMetadataFile(number) + ".",
                                false, "Die synthetische Rohantwort im Szenario ergaenzen.")));
            }
            return SourceResult.of(instanceId, SourceStatus.COLLECTED, selection(target, number, null, null),
                    collectedAt, List.of(), List.of());
        }
        if (kind == BuildSelector.Kind.LAST_FAILED || kind == BuildSelector.Kind.LAST_UNSUCCESSFUL) {
            String json = fixtures.readRaw(scenario, "jenkins/job.json");
            if (json == null) {
                return SourceResult.of(instanceId, SourceStatus.NOT_PRESENT, selection(target, 0, null, null),
                        collectedAt, List.of(), List.of(new OpsError(ErrorCode.NOT_FOUND, instanceId,
                                "Im Szenario fehlt jenkins/job.json; lastFailed ist nicht aufloesbar.", false,
                                "Die synthetische Rohantwort im Szenario ergaenzen.")));
            }
            try {
                BuildRef jobRef = new BuildRef(instanceId, jobPath(target), fallbackBuildNumber(target));
                BuildSelection resolved = parser.parseLastFailed(json, instanceId, jobRef.jobPath());
                return SourceResult.of(instanceId, SourceStatus.COLLECTED, resolved, collectedAt, List.of(),
                        List.of());
            } catch (JenkinsParseException ex) {
                return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, selection(target, 0, null, null),
                        collectedAt, List.of(), List.of(parseError("jenkins/job.json", ex)));
            } catch (RuntimeException ex) {
                return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, selection(target, 0, null, null),
                        collectedAt, List.of(), List.of(parseError("jenkins/job.json", ex)));
            }
        }
        return SourceResult.of(instanceId, SourceStatus.NOT_ATTEMPTED, selection(target, 0, null, null),
                collectedAt, List.of(), List.of(OpsError.of(ErrorCode.INVALID_INPUT, instanceId,
                        "Diese Buildauswahl wird im Fixturebetrieb nicht unterstuetzt.")));
    }

    @Override
    public SourceResult<BuildSnapshot> collectMetadata(ResolvedTarget target, CollectionBudget budget) {
        Instant collectedAt = clock.instant();
        FixtureData scenario;
        try {
            scenario = fixtures.forTarget(target);
        } catch (RuntimeException ex) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptySnapshot(target), collectedAt,
                    List.of(), List.of(OpsError.of(ErrorCode.CONFIG_INCOMPLETE, instanceId,
                            "Das Fixture-Szenario ist nicht aufloesbar.")));
        }
        OpsError transport = transportError(scenario);
        if (transport != null) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptySnapshot(target), collectedAt,
                    List.of("Es wird kein BuildSnapshot geliefert; der Fehler bleibt sichtbar."),
                    List.of(transport));
        }
        BuildRef ref = buildRef(target);
        String file = buildMetadataFile(ref.buildNumber());
        String json = fixtures.readRaw(scenario, file);
        if (json == null) {
            return SourceResult.of(instanceId, SourceStatus.NOT_PRESENT, emptySnapshot(target), collectedAt,
                    List.of(), List.of(new OpsError(ErrorCode.NOT_FOUND, instanceId,
                            "Im Szenario fehlt " + file + ".", false,
                            "Die synthetische Rohantwort im Szenario ergaenzen.")));
        }
        List<String> notes = new ArrayList<>();
        if (budget != null) {
            budget.recordInputBytes(json.getBytes(StandardCharsets.UTF_8).length);
        }
        try {
            BuildSnapshot snapshot = parser.parseBuildMetadata(json, ref, allowedParameterNames(target));
            notes.add("Synthetische Buildmetadaten aus " + file + "; keine Liveabfrage ausgefuehrt.");
            return SourceResult.of(instanceId, SourceStatus.COLLECTED, snapshot, collectedAt,
                    List.copyOf(notes), List.of());
        } catch (JenkinsParseException ex) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptySnapshot(target), collectedAt,
                    List.of(), List.of(parseError(file, ex)));
        } catch (RuntimeException ex) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptySnapshot(target), collectedAt,
                    List.of(), List.of(parseError(file, ex)));
        }
    }

    @Override
    public SourceResult<ConsoleLogEvidence> collectConsoleLog(ResolvedTarget target, CollectionBudget budget) {
        Instant collectedAt = clock.instant();
        FixtureData scenario;
        try {
            scenario = fixtures.forTarget(target);
        } catch (RuntimeException ex) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptyConsoleLog(target), collectedAt,
                    List.of(), List.of(OpsError.of(ErrorCode.CONFIG_INCOMPLETE, instanceId,
                            "Das Fixture-Szenario ist nicht aufloesbar.")));
        }
        OpsError transport = transportError(scenario);
        if (transport != null) {
            return SourceResult.of(instanceId, SourceStatus.UNREACHABLE, emptyConsoleLog(target), collectedAt,
                    List.of("Es wird kein Console-Log geliefert; der Fehler bleibt sichtbar."), List.of(transport));
        }
        BuildRef ref = buildRef(target);
        String file = "jenkins/build-" + ref.buildNumber() + "-console.txt";
        String text = fixtures.readRaw(scenario, file);
        if (text == null) {
            return SourceResult.of(instanceId, SourceStatus.NOT_PRESENT, emptyConsoleLog(target), collectedAt,
                    List.of(), List.of(new OpsError(ErrorCode.NOT_FOUND, instanceId,
                            "Im Szenario fehlt " + file + ".", false,
                            "Die synthetische Rohantwort im Szenario ergaenzen.")));
        }

        long budgetBytes = config.limits().maxBuildLogBytes();
        boolean truncated = text.length() > budgetBytes;
        String excerpt = truncated ? excerpt(text, budgetBytes) : text;
        if (budget != null) {
            budget.recordInputBytes(excerpt.getBytes(StandardCharsets.UTF_8).length);
            if (truncated) {
                budget.recordExhaustion("maxBuildLogBytes=" + budgetBytes + " erreicht");
            }
        }

        List<String> lines = new ArrayList<>();
        for (String line : excerpt.split("\n", -1)) {
            String redacted = Redactor.redact(line).text();
            if (!redacted.isBlank()) {
                lines.add(redacted);
            }
        }
        List<ConsoleLogEvidence.ErrorAnchor> anchors = anchors(lines);
        boolean timestamps = lines.stream().anyMatch(line -> TIMESTAMP.matcher(line).find());
        List<String> notes = new ArrayList<>();
        notes.add("Synthetisches Console-Log aus " + file + "; keine Liveabfrage ausgefuehrt.");
        if (truncated) {
            notes.add("Bytebudget maxBuildLogBytes=" + budgetBytes + " erreicht: es wurden ein Kopf- und "
                    + "ein Endausschnitt gelesen. Das Buildende ist damit teilweise, aber nicht "
                    + "vollstaendig belegt.");
        }
        if (!timestamps) {
            notes.add("Die Zeilen tragen keine Zeitstempel; es werden nur Zeilenreferenzen verwendet.");
        }
        String truncatedReason = truncated
                ? "Bytebudget maxBuildLogBytes=" + budgetBytes + " erreicht; Kopf- und Endausschnitt gelesen, "
                        + "der mittlere Teil des Logs wurde nicht gelesen."
                : "";
        long headBytes = truncated ? Math.max(1, budgetBytes / 4) : 0L;
        ConsoleLogEvidence evidence = new ConsoleLogEvidence(ref, List.copyOf(lines), 1,
                (long) text.length(), excerpt.getBytes(StandardCharsets.UTF_8).length, truncated, truncatedReason,
                timestamps, truncated ? 0L : null, truncated ? headBytes : null, anchors);
        SourceStatus status = truncated ? SourceStatus.TRUNCATED : SourceStatus.COLLECTED;
        return SourceResult.of(instanceId, status, evidence, collectedAt, List.copyOf(notes), List.of());
    }

    // --- Fehler ---------------------------------------------------------------------------

    private OpsError transportError(FixtureData scenario) {
        if (!fixtures.exists(scenario, ERROR_FILE)) {
            return null;
        }
        JsonNode node;
        try {
            node = fixtures.readRawJson(scenario, ERROR_FILE);
        } catch (RuntimeException ex) {
            return new OpsError(ErrorCode.SOURCE_PARSE_ERROR, instanceId,
                    "Die Fehlerdatei " + ERROR_FILE + " ist im Szenario nicht auswertbar.", false,
                    "Die synthetische Fehlerantwort korrigieren.");
        }
        int status = node != null && node.get("httpStatus") != null && node.get("httpStatus").isNumber()
                ? node.get("httpStatus").asInt()
                : 0;
        ErrorCode code = switch (status) {
            case 401 -> ErrorCode.AUTH_REQUIRED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 407 -> ErrorCode.PROXY_AUTH_REQUIRED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 0 -> ErrorCode.SOURCE_PARSE_ERROR;
            default -> ErrorCode.SOURCE_PARSE_ERROR;
        };
        String message = switch (code) {
            case AUTH_REQUIRED -> "Die Jenkins-Antwort ist im Szenario als 401 (Authentifizierung "
                    + "erforderlich) hinterlegt.";
            case FORBIDDEN -> "Die Jenkins-Antwort ist im Szenario als 403 (keine Leseberechtigung) "
                    + "hinterlegt. Das ist ein Rechteproblem des Zugriffs und keine Aussage ueber "
                    + "OpenShift-RBAC.";
            case PROXY_AUTH_REQUIRED -> "Die Jenkins-Antwort ist im Szenario als 407 "
                    + "(Proxyauthentifizierung erforderlich) hinterlegt.";
            case NOT_FOUND -> "Die Jenkins-Antwort ist im Szenario als 404 hinterlegt. Das kann "
                    + "'geloescht' oder 'nicht sichtbar' bedeuten und ist kein sicherer Existenzbeweis.";
            default -> "Die Fehlerdatei " + ERROR_FILE + " nennt keinen auswertbaren HTTP-Status.";
        };
        return new OpsError(code, instanceId, message, false,
                "Den Jenkins-Lesezugriff manuell pruefen; es wird kein Ersatzweg und kein "
                        + "Fixture-Fallback verwendet.");
    }

    private OpsError parseError(String file, RuntimeException ex) {
        return new OpsError(ErrorCode.SOURCE_PARSE_ERROR, instanceId,
                "Die Jenkins-Rohantwort " + file + " ist nicht auswertbar ("
                        + ch.so.agi.ops.security.PathSafety.describe(ex.getClass().getSimpleName()) + ").",
                false, "Die synthetische Rohantwort im Szenario korrigieren.");
    }

    // --- Hilfsmittel ------------------------------------------------------------------------

    private static String buildMetadataFile(int number) {
        return "jenkins/build-" + number + "-metadata.json";
    }

    private BuildRef buildRef(ResolvedTarget target) {
        return target == null || target.buildRef() == null
                ? new BuildRef(instanceId, List.of(), 42)
                : target.buildRef();
    }

    private static int fallbackBuildNumber(ResolvedTarget target) {
        return target == null || target.buildRef() == null ? 42 : target.buildRef().buildNumber();
    }

    private static List<String> jobPath(ResolvedTarget target) {
        return target == null || target.buildRef() == null ? List.of() : target.buildRef().jobPath();
    }

    private List<String> allowedParameterNames(ResolvedTarget target) {
        if (target == null || target.definition() == null || target.definition().configuration() == null) {
            return List.of();
        }
        return target.definition().configuration().allowedParameterNames();
    }

    private BuildSelection selection(ResolvedTarget target, int number, String result, Boolean building) {
        return new BuildSelection(instanceId, jobPath(target), number, result, building,
                number > 0 ? "Konkrete Buildnummer aus dem Auftrag." : "Keine Aufloesung moeglich.", false);
    }

    private static BuildSnapshot emptySnapshot(ResolvedTarget target) {
        BuildRef ref = target == null || target.buildRef() == null ? null : target.buildRef();
        BuildRef effective = ref == null ? new BuildRef("fixture", List.of(), 42) : ref;
        return new BuildSnapshot(effective, null, null, null, null, null, null, false, List.of(), List.of(),
                List.of(), List.of(), null, false, List.of());
    }

    private static ConsoleLogEvidence emptyConsoleLog(ResolvedTarget target) {
        BuildRef ref = target == null || target.buildRef() == null
                ? new BuildRef("fixture", List.of(), 42)
                : target.buildRef();
        return new ConsoleLogEvidence(ref, List.of(), 1, null, 0L, false, "", false, null, null, List.of());
    }

    /** Kopf- und Endausschnitt bei erreichterm Bytebudget; der mittlere Teil bleibt ausgelassen. */
    static String excerpt(String text, long budgetBytes) {
        long headBudget = Math.max(1, budgetBytes / 4);
        long tailBudget = Math.max(1, budgetBytes - headBudget);
        int headEnd = (int) Math.min(headBudget, text.length());
        int tailStart = (int) Math.max(headEnd, text.length() - tailBudget);
        String head = text.substring(0, headEnd);
        String tail = text.substring(tailStart);
        return head + "\n[... " + (tailStart - headEnd) + " Bytes ausgelassen: Bytebudget erreicht ...]\n" + tail;
    }

    private static List<ConsoleLogEvidence.ErrorAnchor> anchors(List<String> lines) {
        List<ConsoleLogEvidence.ErrorAnchor> anchors = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (ErrorMarker marker : ERROR_MARKERS) {
                if (marker.pattern().matcher(line).find()) {
                    anchors.add(new ConsoleLogEvidence.ErrorAnchor(index + 1, line, marker.label()));
                    break;
                }
            }
        }
        return List.copyOf(anchors);
    }

    /** Nur fuer Tests: Anzahl erkannter Fehleranker in einem Text. */
    static int anchorCount(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return anchors(lines).size();
    }
}
