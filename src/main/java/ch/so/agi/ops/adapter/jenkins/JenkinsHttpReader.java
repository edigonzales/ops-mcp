package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.JenkinsDefinition;
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
import ch.so.agi.ops.security.ScopeViolationException;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produktiver, ausschliesslich lesender Jenkins-Adapter.
 *
 * <p>Eigenschaften (S-02, S-03, S-05):</p>
 * <ul>
 *   <li>Nur feste GET-Endpunkte der JSON-Remote-API und des Console-Texts sowie optional
 *       HEAD-Abfragen. Keine {@code config.xml}, keine Script Console, keine
 *       Pipelineausfuehrung, kein Build-Start/-Stopp, kein Workspace-/Credential-Download.</li>
 *   <li>Eigene Proxy- und CA-Konfiguration. {@code proxy.mode: direct} setzt
 *       {@code ProxySelector.of(null)} und wertet {@code NO_PROXY} <em>nicht</em> aus.
 *       Eine zusaetzliche CA wird nur in diesem Client ergaenzt; eine Option zum Abschalten
 *       der TLS-Pruefung existiert nicht.</li>
 *   <li>Weiterleitungen werden selbst behandelt ({@link HttpClient.Redirect#NEVER}). Eine
 *       Weiterleitung auf eine fremde Origin wird als {@code SCOPE_DENIED} abgewiesen; der
 *       {@code Authorization}-Header wird dorthin nie gesendet.</li>
 *   <li>Der Tokenwert erscheint nie in Meldungen, Hinweisen, Fehlern oder Ausnahmen.</li>
 *   <li>Das auftragsweite {@link CollectionBudget} begrenzt Zeit, Aufrufe und Eingangsbytes.</li>
 * </ul>
 */
public final class JenkinsHttpReader implements BuildReader {

    /** Feste, begrenzte Feldliste der Build-Metadaten. */
    public static final String BUILD_TREE = "displayName,building,result,timestamp,duration,estimatedDuration,"
            + "parameters[name,value],"
            + "actions[_class,remoteUrls,lastBuiltRevision[SHA1,branch[name,SHA1]],buildsByBranchName[*]]";

    /** Feste, begrenzte Feldliste der gezielten {@code lastFailed}-Abfrage. */
    public static final String LAST_FAILED_TREE = "lastFailed[number,result,building]";

    /** Feste, begrenzte Feldliste der gezielten {@code lastUnsuccessful}-Abfrage. */
    public static final String LAST_UNSUCCESSFUL_TREE = "lastUnsuccessful[number,result,building]";

    /** Obergrenze einer JSON-Antwort, unabhaengig vom Auftragsbudget. */
    static final long MAX_JSON_BYTES = 2L * 1024 * 1024;

    /** Obergrenze einer Sondenantwort (HEAD bzw. {@code Range: bytes=0-0}). */
    static final long MAX_PROBE_BYTES = 4096;

    /** Maximale Anzahl selbst behandelter Weiterleitungen. */
    static final int MAX_REDIRECTS = 3;

    /** Groesse eines Streaming-Stuecks beim Absuchen der Logmitte. */
    static final long MIDDLE_CHUNK_BYTES = 64 * 1024;

    static final int MAX_HEAD_LINES = 200;
    static final int MAX_TAIL_LINES = 600;
    static final int MAX_MIDDLE_ANCHOR_LINES = 40;

    private static final String ACCEPT_JSON = "application/json";
    private static final String ACCEPT_TEXT = "text/plain";
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("(?i)bytes\\s+\\d+-\\d+/(\\d+)");

    private final OpsConfig config;
    private final String instanceId;
    private final JenkinsDefinition definition;
    private final Clock clock;
    private final JsonMapper mapper;
    private final JenkinsJsonParser parser;
    private final HttpClient httpClient;
    private final ProxySelector proxySelector;
    private final List<OpsError> initializationErrors;
    private final String authorizationHeader;
    private final String tokenValue;

    /**
     * Produktiver Konstruktor. Der HTTP-Client wird aus der Konfiguration aufgebaut
     * (Proxy, zusaetzliche CA, Timeouts, {@code Redirect.NEVER}).
     *
     * @param config     validierte lokale Konfiguration
     * @param instanceId Jenkins-Kennung, z. B. {@code apps-deploy}
     * @param clock      Uhr fuer Erfassungszeiten
     * @param mapper     begrenzter JSON-Mapper
     */
    public JenkinsHttpReader(OpsConfig config, String instanceId, Clock clock, JsonMapper mapper) {
        this(config, instanceId, clock, mapper, null, System::getenv);
    }

    /**
     * Wie der produktive Konstruktor, verwendet aber einen bereits aufgebauten HTTP-Client.
     * Die Konfigurationspruefung (Basisadresse, Token, CA-Datei, Proxy) laeuft unveraendert;
     * ein fehlerhafter Zustand fuehrt weiterhin zu einem erklaerten Fehler statt zu einem
     * stillen Zugriff.
     */
    public JenkinsHttpReader(OpsConfig config, String instanceId, Clock clock, JsonMapper mapper, HttpClient httpClient) {
        this(config, instanceId, clock, mapper, httpClient, System::getenv);
    }

    /**
     * Testnaehe Variante mit austauschbarer Umgebungsaufloesung. Damit bleibt der Pfad
     * {@code token.environmentVar} ohne echte Prozessumgebung pruefbar.
     */
    JenkinsHttpReader(OpsConfig config, String instanceId, Clock clock, JsonMapper mapper, HttpClient httpClient,
            Function<String, String> environment) {
        this.config = Objects.requireNonNull(config, "config");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId fehlt");
        }
        this.instanceId = instanceId;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.parser = new JenkinsJsonParser(this.mapper);
        Function<String, String> env = environment == null ? System::getenv : environment;

        List<OpsError> problems = new ArrayList<>();
        JenkinsDefinition resolved = config.jenkins(instanceId);
        ProxySelector selector = ProxySelector.of(null);
        String auth = null;
        String token = null;
        SSLContext sslContext = null;
        Authenticator proxyAuthenticator = null;

        if (resolved == null) {
            problems.add(error(ErrorCode.CONFIG_INCOMPLETE,
                    "Die Jenkins-Instanz '" + instanceId + "' ist in der Konfiguration nicht vorhanden."));
        } else {
            if (resolved.baseUrl() == null || resolved.baseUrl().isBlank()) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE, "Fuer die Jenkins-Instanz ist keine baseUrl gesetzt."));
            } else {
                try {
                    JenkinsUrlBuilder.requireBase(resolved.baseUrl());
                } catch (ScopeViolationException ex) {
                    problems.add(error(ErrorCode.CONFIG_INVALID,
                            "Die konfigurierte Jenkins-Basisadresse ist unzulaessig: " + ex.getMessage()));
                }
            }
            try {
                selector = proxySelectorFor(resolved.proxy());
            } catch (RuntimeException ex) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE,
                        "Die Proxykonfiguration ist unvollstaendig: " + Redactor.redact(safeMessage(ex)).text()));
            }
            String proxyPassword = null;
            if (resolved.proxy() != null && resolved.proxy().isExplicit() && resolved.proxy().password() != null) {
                try {
                    proxyPassword = resolveSecret(resolved.proxy().password(), env);
                } catch (SecretUnavailableException ex) {
                    problems.add(error(ErrorCode.CONFIG_INCOMPLETE, ex.getMessage()));
                }
                if (proxyPassword == null || proxyPassword.isBlank()) {
                    problems.add(error(ErrorCode.CONFIG_INCOMPLETE,
                            "Die Proxykennwort-Referenz ist gesetzt, liefert aber keinen Wert."));
                    proxyPassword = null;
                }
            }
            if (resolved.proxy() != null && resolved.proxy().isExplicit() && resolved.proxy().username() != null
                    && proxyPassword != null) {
                proxyAuthenticator = proxyAuthenticator(resolved.proxy().username(), proxyPassword);
            }
            try {
                token = resolveSecret(resolved.token(), env);
            } catch (SecretUnavailableException ex) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE, ex.getMessage()));
            }
            if (token == null || token.isBlank()) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE,
                        "Es ist kein Jenkins-API-Token verfuegbar (token.file bzw. token.environmentVar fehlt "
                                + "oder liefert keinen Wert)."));
            } else {
                auth = authorizationHeader(resolved.username(), token);
            }
            if (resolved.tls() != null && resolved.tls().additionalCaFile() != null) {
                try {
                    sslContext = sslContextWithAdditionalCa(resolved.tls().additionalCaFile());
                } catch (GeneralSecurityException | IOException | RuntimeException ex) {
                    problems.add(error(ErrorCode.CONFIG_INVALID,
                            "Die konfigurierte zusaetzliche CA-Datei konnte nicht geladen werden; es wird keine "
                                    + "TLS-Pruefung abgeschaltet. Grund: " + Redactor.redact(safeMessage(ex)).text()));
                }
            }
        }

        this.definition = resolved;
        this.proxySelector = selector;
        this.tokenValue = token;
        this.authorizationHeader = auth;
        this.initializationErrors = List.copyOf(problems);
        if (httpClient != null) {
            this.httpClient = httpClient;
        } else if (problems.isEmpty()) {
            this.httpClient = buildHttpClient(config, selector, sslContext, proxyAuthenticator);
        } else {
            this.httpClient = null;
        }
    }

    @Override
    public String sourceId() {
        return instanceId;
    }

    /**
     * Redigierter Konfigurationsnachweis des Transports. Enthaelt bewusst keine Zugangsdaten
     * und keinen Tokenwert; dient Tests und einem spaeteren {@code doctor}.
     */
    public Map<String, Object> transportConfiguration() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instanceId", instanceId);
        map.put("baseUrlConfigured", definition != null && definition.baseUrl() != null);
        JenkinsDefinition.ProxySettings proxy = definition == null ? null : definition.proxy();
        map.put("proxyMode", proxy == null ? JenkinsDefinition.ProxySettings.MODE_DIRECT : proxy.mode());
        if (proxy != null && proxy.isExplicit()) {
            map.put("proxyHost", proxy.host());
            map.put("proxyPort", proxy.port());
            map.put("proxyCredentialsConfigured", proxy.password() != null && proxy.password().isResolvable());
        }
        map.put("additionalCaConfigured",
                definition != null && definition.tls() != null && definition.tls().additionalCaFile() != null);
        // Es gibt keine Option zum Abschalten der TLS-Pruefung; das Feld haelt das ausdruecklich fest.
        map.put("tlsVerificationDisabled", false);
        map.put("insecureSkipVerifySupported", false);
        map.put("connectTimeoutSeconds", config.limits().connectTimeoutSeconds());
        map.put("requestTimeoutSeconds", config.limits().requestTimeoutSeconds());
        map.put("redirectPolicy", "NEVER mit eigener Behandlung; fremde Origin wird abgewiesen");
        map.put("authentication", authorizationHeader == null ? "nicht-verfuegbar"
                : (definition != null && definition.username() != null && !definition.username().isBlank()
                        ? "basic"
                        : "bearer"));
        map.put("environmentProxyVariablesEvaluated", false);
        map.put("initializationErrors", initializationErrors.stream().map(OpsError::code).map(Enum::name).toList());
        return map;
    }

    /** Der tatsaechlich wirksame Proxyselektor; macht {@code NO_PROXY} irrelevant. */
    ProxySelector proxySelector() {
        return proxySelector;
    }

    @Override
    public SourceResult<BuildSelection> resolveBuild(ResolvedTarget target, BuildSelector selector) {
        Instant at = clock.instant();
        List<String> notes = new ArrayList<>();
        List<OpsError> errors = new ArrayList<>();
        if (!initializationErrors.isEmpty()) {
            errors.addAll(initializationErrors);
            return failure(placeholderSelection(target), SourceStatus.UNREACHABLE, at, notes, errors);
        }
        OpsError invalid = validateTarget(target);
        if (invalid != null) {
            errors.add(invalid);
            return failure(placeholderSelection(target), statusFor(invalid.code()), at, notes, errors);
        }
        if (selector == null || selector.kind() == null) {
            OpsError error = error(ErrorCode.INVALID_INPUT, "Es wurde keine Buildauswahl uebergeben.");
            errors.add(error);
            return failure(placeholderSelection(target), statusFor(error.code()), at, notes, errors);
        }
        List<String> jobPath = selector.jobPath().isEmpty() ? target.buildRef().jobPath() : selector.jobPath();
        OpsError jobPathProblem = validateJobPath(jobPath);
        if (jobPathProblem != null) {
            errors.add(jobPathProblem);
            return failure(placeholderSelection(target), statusFor(jobPathProblem.code()), at, notes, errors);
        }

        if (selector.kind() == BuildSelector.Kind.NUMBER) {
            Integer number = selector.number();
            if (selector.buildUrl() != null && !selector.buildUrl().isBlank()) {
                try {
                    JenkinsUrlBuilder.ParsedBuildLink link = JenkinsUrlBuilder.parseBrowserLink(
                            baseUrl(), definition.allowedJobPaths(), selector.buildUrl());
                    if (number != null && number.intValue() != link.buildNumber()) {
                        OpsError error = error(ErrorCode.INVALID_INPUT,
                                "Die angegebene Buildnummer und der Browserlink widersprechen sich.");
                        errors.add(error);
                        return failure(placeholderSelection(target), statusFor(error.code()), at, notes, errors);
                    }
                    if (!link.jobPath().equals(jobPath)) {
                        OpsError error = error(ErrorCode.SCOPE_DENIED,
                                "Der Browserlink nennt einen anderen Jobpfad als die konfigurierte Zuordnung.");
                        errors.add(error);
                        return failure(placeholderSelection(target), statusFor(error.code()), at, notes, errors);
                    }
                    number = link.buildNumber();
                } catch (ScopeViolationException ex) {
                    OpsError error = error(ErrorCode.SCOPE_DENIED,
                            "Der uebergebene Browserlink wurde abgewiesen: " + Redactor.redact(safeMessage(ex)).text());
                    errors.add(error);
                    return failure(placeholderSelection(target), statusFor(error.code()), at, notes, errors);
                }
            }
            if (number == null || number <= 0) {
                OpsError error = error(ErrorCode.INVALID_INPUT, "Bei kind=NUMBER fehlt eine positive Buildnummer.");
                errors.add(error);
                return failure(placeholderSelection(target), statusFor(error.code()), at, notes, errors);
            }
            // Kein Netzaufruf: die Nummer wird uebernommen. Ein Aufruf wird fuer die spaetere
            // Metadatenabfrage freigehalten.
            target.budget().tryConsumeRequest();
            notes.add("Explizite Buildnummer uebernommen; fuer die Aufloesung war kein Netzaufruf noetig.");
            BuildSelection selection = new BuildSelection(instanceId, jobPath, number, null, null,
                    "Explizite Buildnummer aus dem Auftrag.", false);
            return SourceResult.of(instanceId, SourceStatus.COLLECTED, selection, at, notes, errors);
        }

        boolean lastFailed = selector.kind() == BuildSelector.Kind.LAST_FAILED;
        String tree = lastFailed ? LAST_FAILED_TREE : LAST_UNSUCCESSFUL_TREE;
        String fieldName = lastFailed ? "lastFailed" : "lastUnsuccessful";
        if (!target.budget().tryConsumeRequest()) {
            OpsError error = budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage.");
            errors.add(error);
            return failure(placeholderSelection(target), SourceStatus.NOT_ATTEMPTED, at, notes, errors);
        }
        String url = JenkinsUrlBuilder.jobApiUrl(baseUrl(), jobPath, tree);
        Transport transport = execute("GET", url, null, null, MAX_JSON_BYTES, target.budget(), ACCEPT_JSON);
        if (transport.failed()) {
            errors.add(transport.error());
            return failure(placeholderSelection(target), statusFor(transport.error().code()), at, notes, errors);
        }
        OpsError notJson = requireJson(transport);
        if (notJson != null) {
            errors.add(notJson);
            return failure(placeholderSelection(target), statusFor(notJson.code()), at, notes, errors);
        }
        try {
            BuildSelection selection = lastFailed
                    ? parser.parseLastFailed(text(transport), instanceId, jobPath)
                    : parser.parseLastUnsuccessful(text(transport), instanceId, jobPath);
            notes.add("Jenkins " + fieldName + " wurde genau einmal zu einer konkreten Buildnummer aufgeloest "
                    + "und danach nicht weiterverwendet.");
            return SourceResult.of(instanceId, SourceStatus.COLLECTED, selection, at, notes, errors);
        } catch (JenkinsParseException ex) {
            OpsError error = error(ex.code(), "Jenkins " + fieldName + ": " + Redactor.redact(safeMessage(ex)).text());
            errors.add(error);
            SourceStatus status = ex.code() == ErrorCode.NOT_FOUND ? SourceStatus.UNREACHABLE
                    : statusFor(ex.code());
            return failure(placeholderSelection(target), status, at, notes, errors);
        }
    }

    @Override
    public SourceResult<BuildSnapshot> collectMetadata(ResolvedTarget target, CollectionBudget budget) {
        Instant at = clock.instant();
        List<String> notes = new ArrayList<>();
        List<OpsError> errors = new ArrayList<>();
        if (!initializationErrors.isEmpty()) {
            errors.addAll(initializationErrors);
            return metadataFailure(placeholderRef(target), SourceStatus.UNREACHABLE, at, notes, errors);
        }
        OpsError invalid = validateTarget(target);
        if (invalid != null) {
            errors.add(invalid);
            return metadataFailure(placeholderRef(target), statusFor(invalid.code()), at, notes, errors);
        }
        BuildRef ref = target.buildRef();
        OpsError jobPathProblem = validateJobPath(ref.jobPath());
        if (jobPathProblem != null) {
            errors.add(jobPathProblem);
            return metadataFailure(ref, statusFor(jobPathProblem.code()), at, notes, errors);
        }
        if (!budget.tryConsumeRequest()) {
            errors.add(budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage."));
            return metadataFailure(ref, SourceStatus.NOT_ATTEMPTED, at, notes, errors);
        }
        String url = JenkinsUrlBuilder.buildApiUrl(baseUrl(), ref.jobPath(), ref.buildNumber(), BUILD_TREE);
        long maxBytes = Math.min(MAX_JSON_BYTES, Math.max(4096, budget.maxTotalInputBytes() - budget.inputBytesUsed()));
        Transport transport = execute("GET", url, null, null, maxBytes, budget, ACCEPT_JSON);
        if (transport.failed()) {
            errors.add(transport.error());
            return metadataFailure(ref, statusFor(transport.error().code()), at, notes, errors);
        }
        if (transport.capped()) {
            OpsError error = error(ErrorCode.DATA_TRUNCATED,
                    "Die Jenkins-Antwort war groesser als das zulaessige Budget und wurde abgeschnitten; "
                            + "sie wird nicht ausgewertet.");
            errors.add(error);
            return metadataFailure(ref, SourceStatus.UNREACHABLE, at, notes, errors);
        }
        OpsError notJson = requireJson(transport);
        if (notJson != null) {
            errors.add(notJson);
            return metadataFailure(ref, statusFor(notJson.code()), at, notes, errors);
        }
        try {
            BuildSnapshot snapshot = parser.parseBuildMetadata(text(transport), ref, allowedParameterNames());
            notes.addAll(snapshot.notes());
            notes.add("Nur erlaubte Parameternamen werden uebernommen; alle anderen bleiben auf Namen beschraenkt.");
            return SourceResult.of(instanceId, SourceStatus.COLLECTED, snapshot, at, notes, errors);
        } catch (JenkinsParseException ex) {
            OpsError error = error(ex.code(), "Jenkins-Buildmetadaten: " + Redactor.redact(safeMessage(ex)).text());
            errors.add(error);
            return metadataFailure(ref, statusFor(error.code()), at, notes, errors);
        }
    }

    @Override
    public SourceResult<ConsoleLogEvidence> collectConsoleLog(ResolvedTarget target, CollectionBudget budget) {
        Instant at = clock.instant();
        List<String> notes = new ArrayList<>();
        List<OpsError> errors = new ArrayList<>();
        if (!initializationErrors.isEmpty()) {
            errors.addAll(initializationErrors);
            return consoleFailure(placeholderRef(target), SourceStatus.UNREACHABLE, at, notes, errors);
        }
        OpsError invalid = validateTarget(target);
        if (invalid != null) {
            errors.add(invalid);
            return consoleFailure(placeholderRef(target), statusFor(invalid.code()), at, notes, errors);
        }
        BuildRef ref = target.buildRef();
        OpsError jobPathProblem = validateJobPath(ref.jobPath());
        if (jobPathProblem != null) {
            errors.add(jobPathProblem);
            return consoleFailure(ref, statusFor(jobPathProblem.code()), at, notes, errors);
        }
        String url = JenkinsUrlBuilder.consoleTextUrl(baseUrl(), ref.jobPath(), ref.buildNumber());
        ConsoleLogRead read = readConsoleLog(url, budget, notes);
        if (read.fatalError() != null) {
            notes.addAll(read.notes());
            errors.add(read.fatalError());
            return consoleFailure(ref, statusFor(read.fatalError().code()), at, notes, errors);
        }
        notes.addAll(read.notes());
        boolean empty = read.lines().isEmpty();
        if (empty) {
            notes.add("Das Console-Log ist leer; es liegt kein Textbeleg vor.");
        }
        ConsoleLogEvidence evidence = new ConsoleLogEvidence(
                ref,
                read.lines(),
                read.firstLineNumber(),
                read.totalBytes(),
                read.bytesRead(),
                read.truncated(),
                read.truncatedReason(),
                read.timestampsAvailable(),
                read.headRequestedFromByte(),
                read.tailRequestedFromByte(),
                read.anchors());
        SourceStatus status = empty ? SourceStatus.NOT_PRESENT
                : (read.truncated() ? SourceStatus.TRUNCATED : SourceStatus.COLLECTED);
        if (read.truncated()) {
            notes.add("Das Console-Log wurde nicht vollstaendig gelesen: " + read.truncatedReason());
        }
        return SourceResult.of(instanceId, status, evidence, at, notes, errors);
    }

    // ------------------------------------------------------------------
    // Console-Log
    // ------------------------------------------------------------------

    private ConsoleLogRead readConsoleLog(String url, CollectionBudget budget, List<String> notes) {
        long byteBudget = logByteBudget(budget);
        if (byteBudget <= 0) {
            return ConsoleLogRead.failure(budgetError("Das Gesamteingangsbudget ist aufgebraucht; "
                    + "es wird kein Console-Log mehr gelesen."), null, 0, List.of());
        }
        SizeProbe probe = probeSize(url, budget);
        long bytesRead = probe.bytesRead();
        if (probe.fatalError() != null) {
            return ConsoleLogRead.failure(probe.fatalError(), probe.totalBytes(), bytesRead, List.of());
        }
        if (probe.softError() != null) {
            notes.add("Die Loggroesse war nicht ermittelbar (" + probe.softError().message()
                    + "); es wird begrenzt ab dem Loganfang gelesen.");
        }

        if (probe.totalBytes() == null || probe.totalBytes() <= byteBudget) {
            if (!budget.tryConsumeRequest()) {
                return ConsoleLogRead.failure(budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage."),
                        probe.totalBytes(), bytesRead, List.of());
            }
            Transport transport = execute("GET", url, null, null, byteBudget + 1, budget, ACCEPT_TEXT);
            bytesRead += transport.bytesRead();
            if (transport.failed()) {
                return ConsoleLogRead.failure(transport.error(), probe.totalBytes(), bytesRead, List.of());
            }
            OpsError html = requirePlainText(transport);
            if (html != null) {
                return ConsoleLogRead.failure(html, probe.totalBytes(), bytesRead, List.of());
            }
            List<String> lines = ConsoleLogSupport.splitLines(ConsoleLogSupport.decode(transport.body()));
            boolean truncated = transport.capped();
            String reason = truncated
                    ? "Das Bytebudget von " + byteBudget + " Bytes wurde vor dem Ende erreicht; "
                            + "das Buildende wurde NICHT gelesen (es liegt nur ein Praefix vor)."
                    : "";
            return new ConsoleLogRead(lines, lines.isEmpty() ? -1 : 1, probe.totalBytes(), bytesRead, truncated,
                    reason, ConsoleLogSupport.hasTimestamps(lines), 0L, null,
                    ConsoleLogSupport.anchors(lines, 1), List.of(), null);
        }

        long total = probe.totalBytes();
        List<String> notesOut = new ArrayList<>();
        long headBudget = Math.max(1, byteBudget / 4);
        long tailBudget = Math.max(1, byteBudget / 2);
        long middleBudget = Math.max(0, byteBudget - headBudget - tailBudget);
        long headEnd = Math.min(headBudget, total);
        long tailFrom = Math.max(headEnd, total - tailBudget);

        // 1) Kopfausschnitt
        if (!budget.tryConsumeRequest()) {
            return ConsoleLogRead.failure(budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage."),
                    total, bytesRead, notesOut);
        }
        Transport headTransport = execute("GET", url, 0L, headEnd - 1, headBudget, budget, ACCEPT_TEXT);
        bytesRead += headTransport.bytesRead();
        if (headTransport.failed()) {
            return ConsoleLogRead.failure(headTransport.error(), total, bytesRead, notesOut);
        }
        OpsError html = requirePlainText(headTransport);
        if (html != null) {
            return ConsoleLogRead.failure(html, total, bytesRead, notesOut);
        }
        String headRaw = ConsoleLogSupport.decode(headTransport.body());
        boolean headAlignedEnd = headRaw.endsWith("\n");
        List<String> headAll = ConsoleLogSupport.splitLines(headRaw);
        String carry = "";
        if (!headAlignedEnd && !headAll.isEmpty()) {
            carry = headAll.remove(headAll.size() - 1);
            notesOut.add("Die letzte Zeile des Kopfausschnitts war unvollstaendig und wurde verworfen.");
        }
        long headLineCount = headAll.size();
        List<String> headLines;
        if (headAll.size() > MAX_HEAD_LINES) {
            headLines = new ArrayList<>(headAll.subList(0, MAX_HEAD_LINES));
            notesOut.add("Der Kopfausschnitt wurde auf die ersten " + MAX_HEAD_LINES + " Zeilen begrenzt.");
        } else {
            headLines = new ArrayList<>(headAll);
        }

        // 2) Mittelteil zeilenweise bis zum Budget absuchen
        long scanPosition = headEnd;
        long linesCompleted = 0;
        boolean middleComplete = scanPosition >= tailFrom;
        boolean rangeIgnored = false;
        List<String> middleAnchorLines = new ArrayList<>();
        List<ConsoleLogEvidence.ErrorAnchor> middleAnchors = new ArrayList<>();
        long middleScanned = 0;
        while (!middleComplete && middleBudget > 0 && middleScanned < middleBudget
                && budget.requestsUsed() < budget.maxRequests()) {
            long from = scanPosition;
            long to = Math.min(tailFrom - 1, from + MIDDLE_CHUNK_BYTES - 1);
            long cap = to - from + 1;
            if (!budget.tryConsumeRequest()) {
                break;
            }
            Transport chunk = execute("GET", url, from, to, cap, budget, ACCEPT_TEXT);
            bytesRead += chunk.bytesRead();
            middleScanned += chunk.bytesRead();
            if (chunk.failed()) {
                notesOut.add("Der Mittelteil des Logs konnte nicht weiter gelesen werden: "
                        + chunk.error().message());
                break;
            }
            if (chunk.status() == 200 && from > 0) {
                rangeIgnored = true;
                notesOut.add("Der Server beantwortet Range-Anfragen nicht; der Mittelteil und das Buildende "
                        + "konnten nicht gelesen werden.");
                break;
            }
            String text = carry + ConsoleLogSupport.decode(chunk.body());
            carry = "";
            int index;
            while ((index = text.indexOf('\n')) >= 0) {
                String line = ConsoleLogSupport.stripCr(text.substring(0, index));
                text = text.substring(index + 1);
                linesCompleted++;
                long lineNumber = headLineCount + linesCompleted;
                String marker = ConsoleLogSupport.marker(line);
                if (marker != null && middleAnchors.size() < MAX_MIDDLE_ANCHOR_LINES) {
                    middleAnchorLines.add(line);
                    middleAnchors.add(new ConsoleLogEvidence.ErrorAnchor((int) lineNumber, line, marker));
                }
            }
            carry = text;
            scanPosition = to + 1;
            middleComplete = scanPosition >= tailFrom;
        }
        if (!middleComplete && !rangeIgnored && middleBudget > 0) {
            notesOut.add("Der Mittelteil des Logs wurde nur bis zum Bytebudget von " + middleBudget
                    + " Bytes abgesucht.");
        }

        // 3) Endausschnitt mit Ausrichtung an der Zeilengrenze
        List<String> tailLines = new ArrayList<>();
        Long tailRequestFrom = null;
        int tailFirstNumber = -1;
        if (rangeIgnored) {
            notesOut.add("Es wurde kein Endausschnitt gelesen, weil der Server Range-Anfragen nicht beantwortet.");
        } else if (tailFrom < total) {
            long requestFrom = tailFrom > 0 ? tailFrom - 1 : 0;
            long cap = total - requestFrom;
            if (!budget.tryConsumeRequest()) {
                notesOut.add("Das Auftragsbudget erlaubte keinen Endausschnitt des Logs mehr.");
            } else {
                Transport tailTransport = execute("GET", url, requestFrom, null, cap, budget, ACCEPT_TEXT);
                bytesRead += tailTransport.bytesRead();
                if (tailTransport.failed()) {
                    notesOut.add("Der Endausschnitt des Logs konnte nicht gelesen werden: "
                            + tailTransport.error().message());
                } else if (tailTransport.status() == 200 && requestFrom > 0) {
                    rangeIgnored = true;
                    notesOut.add("Der Server beantwortet Range-Anfragen nicht; das Buildende wurde NICHT gelesen.");
                } else {
                    byte[] body = tailTransport.body();
                    boolean aligned = tailFrom == 0;
                    if (!aligned && body.length > 0 && body[0] == '\n') {
                        aligned = true;
                    }
                    byte[] slice = body;
                    if (tailFrom > 0 && body.length > 0) {
                        slice = new byte[body.length - 1];
                        System.arraycopy(body, 1, slice, 0, slice.length);
                    }
                    String tailText = ConsoleLogSupport.decode(slice);
                    boolean numberKnown = middleComplete;
                    if (middleComplete) {
                        tailFirstNumber = (int) (headLineCount + linesCompleted + 1);
                        if (!aligned) {
                            tailText = carry + tailText;
                        }
                    } else if (!aligned) {
                        int breakIndex = tailText.indexOf('\n');
                        tailText = breakIndex >= 0 ? tailText.substring(breakIndex + 1) : "";
                        notesOut.add("Die erste Zeile des Endausschnitts begann vor dem gelesenen Bereich und wurde "
                                + "verworfen; " + ConsoleLogSupport.NO_LINE_NUMBER + ".");
                    }
                    List<String> tailAll = ConsoleLogSupport.splitLines(tailText);
                    int dropped = Math.max(0, tailAll.size() - MAX_TAIL_LINES);
                    if (dropped > 0) {
                        tailAll = new ArrayList<>(tailAll.subList(dropped, tailAll.size()));
                        if (numberKnown) {
                            tailFirstNumber += dropped;
                        }
                        notesOut.add("Der Endausschnitt wurde auf die letzten " + MAX_TAIL_LINES + " Zeilen begrenzt.");
                    }
                    tailLines = new ArrayList<>(tailAll);
                    tailRequestFrom = tailFrom;
                    if (!numberKnown) {
                        tailFirstNumber = -1;
                    }
                }
            }
        }

        // 4) Zusammensetzung mit ausdruecklichen Lueckenmarkierungen
        List<String> composed = new ArrayList<>(headLines);
        StringBuilder gap = new StringBuilder("[... ");
        gap.append("Nicht zusammenhaengender Ausschnitt: Kopf bis Byte ").append(headTransport.bytesRead());
        gap.append(", Mittelteil ab Byte ").append(headEnd).append(" bis ").append(tailFrom);
        gap.append(" nur ausschnittsweise gelesen (").append(middleScanned).append(" von ")
                .append(Math.max(0, tailFrom - headEnd)).append(" Bytes abgesucht)");
        gap.append("; Gesamtgroesse ").append(total).append(" Bytes, gelesen ").append(bytesRead).append(" Bytes");
        gap.append(". Zeilennummern nach dieser Luecke sind ohne Gewaehr. ...]");
        if (!headLines.isEmpty()) {
            composed.add(gap.toString());
        }
        if (!middleAnchorLines.isEmpty()) {
            composed.add("[... Fehlerzeilen aus dem nicht vollstaendig gelesenen Mittelteil (" 
                    + middleAnchors.size() + " Zeilen): ...]");
            composed.addAll(middleAnchorLines);
        }
        if (!tailLines.isEmpty()) {
            composed.add("[... Endausschnitt des Logs ab Byte " + tailFrom + "; "
                    + (tailFirstNumber > 0 ? "erste vollstaendige Zeile Nummer " + tailFirstNumber
                            : "Zeilennummern nicht bestimmbar")
                    + ". ...]");
            composed.addAll(tailLines);
        }

        List<ConsoleLogEvidence.ErrorAnchor> anchors = new ArrayList<>();
        anchors.addAll(ConsoleLogSupport.anchors(headLines, 1));
        anchors.addAll(middleAnchors);
        anchors.addAll(ConsoleLogSupport.anchors(tailLines, middleComplete ? tailFirstNumber : -1));

        String reason = rangeIgnored
                ? "Der Server beantwortet keine Range-Anfragen. Es liegen nur Kopfausschnitte vor; "
                        + "das Buildende wurde NICHT untersucht."
                : "Das Log ist groesser als das Bytebudget von " + byteBudget + " Bytes. Gelesen wurden ein Kopf- "
                        + "und ein Endausschnitt sowie " + middleAnchors.size()
                        + " Fehlerzeilen aus dem Mittelteil; dazwischen liegende Bytes wurden NICHT gelesen.";
        return new ConsoleLogRead(composed, 1, total, bytesRead, true, reason,
                ConsoleLogSupport.hasTimestamps(composed), 0L, tailRequestFrom, anchors, notesOut, null);
    }

    private SizeProbe probeSize(String url, CollectionBudget budget) {
        long read = 0;
        if (!budget.tryConsumeRequest()) {
            return new SizeProbe(null, read, budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage."),
                    null);
        }
        Transport head = execute("HEAD", url, null, null, MAX_PROBE_BYTES, budget, ACCEPT_TEXT);
        read += head.bytesRead();
        if (!head.failed()) {
            Long length = contentLength(head);
            if (length != null) {
                return new SizeProbe(length, read, null, null);
            }
        } else if (fatalTransportError(head.error())) {
            return new SizeProbe(null, read, head.error(), null);
        }
        if (!budget.tryConsumeRequest()) {
            return new SizeProbe(null, read, budgetError("Das Auftragsbudget erlaubt keine weitere Jenkins-Abfrage."),
                    null);
        }
        Transport range = execute("GET", url, 0L, 0L, MAX_PROBE_BYTES, budget, ACCEPT_TEXT);
        read += range.bytesRead();
        if (!range.failed()) {
            Long total = totalFromContentRange(range);
            if (total == null && range.status() == 200) {
                total = contentLength(range);
            }
            return new SizeProbe(total, read, null, null);
        }
        if (fatalTransportError(range.error())) {
            return new SizeProbe(null, read, range.error(), null);
        }
        // Die Groesse bleibt unbekannt; der Aufrufer liest begrenzt ab dem Anfang.
        return new SizeProbe(null, read, null, range.error());
    }

    /**
     * Bytebudget des Console-Logs: das Minimum aus konfiguriertem Logbudget und dem noch
     * verbleibenden Gesamteingangsbudget. Das Gesamtbudget wird nicht ueberschritten; ist es
     * aufgebraucht, wird gar nicht gelesen.
     */
    private long logByteBudget(CollectionBudget budget) {
        long configured = Math.max(1, config.limits().maxBuildLogBytes());
        long remaining = Math.max(0, budget.maxTotalInputBytes() - budget.inputBytesUsed());
        return Math.min(configured, remaining);
    }

    private Long contentLength(Transport transport) {
        if (transport.status() != 200 && transport.status() != 206) {
            return null;
        }
        String value = transport.headers().firstValue("Content-Length").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long totalFromContentRange(Transport transport) {
        String value = transport.headers().firstValue("Content-Range").orElse(null);
        if (value == null) {
            return null;
        }
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(value);
        if (matcher.find()) {
            try {
                return Long.valueOf(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private Transport execute(String method, String url, Long rangeFrom, Long rangeTo, long maxBodyBytes,
            CollectionBudget budget, String accept) {
        if (!initializationErrors.isEmpty()) {
            return Transport.failure(initializationErrors.get(0));
        }
        Duration timeout = config.limits().requestTimeout();
        timeout = budget.effectiveTimeout(timeout, clock.instant());
        if (timeout.isZero() || timeout.isNegative()) {
            return Transport.failure(budgetError("Das auftragsweite Zeitbudget ist vor der Jenkins-Abfrage "
                    + "abgelaufen."));
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return Transport.failure(error(ErrorCode.SOURCE_PARSE_ERROR, "Die erzeugte Jenkins-URL ist ungueltig."));
        }
        OpsError originProblem = requireConfiguredOrigin(uri);
        if (originProblem != null) {
            return Transport.failure(originProblem);
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", accept)
                    .header("User-Agent", "agi-ops-agent/1.0 (read-only)");
            if (authorizationHeader != null) {
                builder.header("Authorization", authorizationHeader);
            }
            if (rangeFrom != null) {
                builder.header("Range", rangeTo == null
                        ? "bytes=" + rangeFrom + "-"
                        : "bytes=" + rangeFrom + "-" + rangeTo);
            }
            builder.method(method, HttpRequest.BodyPublishers.noBody());

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                return Transport.failure(transportError(ex));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Transport.failure(error(ErrorCode.INTERNAL_ERROR, "Die Jenkins-Abfrage wurde unterbrochen."));
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                drain(response.body());
                if (location == null || location.isBlank()) {
                    return Transport.failure(error(ErrorCode.SOURCE_PARSE_ERROR,
                            "Jenkins antwortete mit einer Weiterleitung ohne Location-Header."));
                }
                URI target;
                try {
                    target = uri.resolve(location.trim());
                } catch (IllegalArgumentException ex) {
                    return Transport.failure(scopeDenied("Die Weiterleitung nennt ein unzulaessiges Ziel."));
                }
                if (target.getUserInfo() != null) {
                    return Transport.failure(scopeDenied(
                            "Die Weiterleitung nennt Zugangsdaten (Userinfo) und wird abgewiesen."));
                }
                OpsError targetProblem = requireConfiguredOrigin(target);
                if (targetProblem != null) {
                    return Transport.failure(scopeDenied("Weiterleitung auf eine fremde Origin abgewiesen. "
                            + "Der Authorization-Header wurde nicht an " + safeHost(target)
                            + " gesendet (S-02)."));
                }
                uri = target;
                continue;
            }
            BoundedBody body;
            try (InputStream in = response.body()) {
                body = readBounded(in, maxBodyBytes);
            } catch (IOException ex) {
                return Transport.failure(transportError(ex));
            }
            if (budget != null) {
                budget.recordInputBytes(body.bytes().length);
            }
            if (status == 200 || status == 206) {
                return new Transport(status, response.headers(), body.bytes(), body.capped(),
                        body.bytes().length, null);
            }
            return Transport.failure(statusError(status));
        }
        return Transport.failure(scopeDenied("Zu viele Weiterleitungen; die Anfrage wird abgebrochen."));
    }

    private OpsError requireConfiguredOrigin(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return scopeDenied("Die Adresse hat keinen Hostnamen und wird abgewiesen.");
        }
        if (definition == null || definition.baseUrl() == null) {
            return error(ErrorCode.CONFIG_INCOMPLETE, "Es ist keine Jenkins-Basisadresse konfiguriert.");
        }
        String expected = JenkinsUrlBuilder.origin(definition.baseUrl());
        String actual;
        try {
            actual = JenkinsUrlBuilder.origin(uri.toString());
        } catch (ScopeViolationException ex) {
            return scopeDenied("Die Adresse ist keine erlaubte HTTP(S)-Adresse.");
        }
        if (!expected.equals(actual)) {
            return scopeDenied("Die Adresse gehoert nicht zur konfigurierten Jenkins-Instanz "
                    + "(erwartet " + expected + ", erhalten " + actual + ").");
        }
        return null;
    }

    private HttpClient buildHttpClient(OpsConfig opsConfig, ProxySelector selector, SSLContext sslContext,
            Authenticator proxyAuthenticator) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                // Weiterleitungen werden selbst behandelt, damit kein Authorization-Header an
                // eine fremde Origin gelangt.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(opsConfig.limits().connectTimeout())
                .proxy(selector);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        if (proxyAuthenticator != null) {
            builder.authenticator(proxyAuthenticator);
        }
        return builder.build();
    }

    /**
     * {@code direct} bedeutet ausdruecklich "kein Proxy" und wertet {@code NO_PROXY}/andere
     * Umgebungsvariablen nicht aus. {@code explicit} verwendet genau den konfigurierten Proxy.
     */
    static ProxySelector proxySelectorFor(JenkinsDefinition.ProxySettings proxy) {
        if (proxy == null || proxy.isDirect()) {
            return ProxySelector.of(null);
        }
        if (proxy.host() == null || proxy.host().isBlank() || proxy.port() == null
                || proxy.port() <= 0 || proxy.port() > 65535) {
            throw new IllegalArgumentException("Bei proxy.mode=explicit sind host und port erforderlich.");
        }
        return ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port()));
    }

    private static Authenticator proxyAuthenticator(String username, String password) {
        char[] secret = password.toCharArray();
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                    return new PasswordAuthentication(username, secret);
                }
                return null;
            }
        };
    }

    /**
     * JVM-Standardtruststore plus lokal konfigurierte CA. Es wird ausschliesslich ergaenzt;
     * eine Abschaltung der Pruefung ist nicht vorgesehen.
     */
    static SSLContext sslContextWithAdditionalCa(Path caFile) throws GeneralSecurityException, IOException {
        if (caFile == null || !Files.isRegularFile(caFile)) {
            throw new CertificateException("Die konfigurierte CA-Datei ist nicht lesbar.");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> additional;
        try (InputStream in = Files.newInputStream(caFile)) {
            additional = new ArrayList<>(factory.generateCertificates(in));
        }
        if (additional.isEmpty()) {
            throw new CertificateException("Die konfigurierte CA-Datei enthaelt kein Zertifikat.");
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        TrustManagerFactory defaultFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultFactory.init((KeyStore) null);
        int index = 0;
        for (TrustManager trustManager : defaultFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager x509) {
                for (X509Certificate certificate : x509.getAcceptedIssuers()) {
                    keyStore.setCertificateEntry("jvm-" + (index++), certificate);
                }
            }
        }
        for (Certificate certificate : additional) {
            keyStore.setCertificateEntry("additional-" + (index++), certificate);
        }
        TrustManagerFactory merged = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        merged.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, merged.getTrustManagers(), null);
        return context;
    }

    private static BoundedBody readBounded(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        boolean capped = false;
        int read;
        while ((read = in.read(buffer)) != -1) {
            long allowance = Math.max(0, max - total);
            int allowed = (int) Math.min(read, allowance);
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
                total += allowed;
            }
            if (allowed < read) {
                capped = true;
                break;
            }
        }
        return new BoundedBody(out.toByteArray(), capped);
    }

    /** Liest und verwirft einen Antwortkoerper (z. B. einer Weiterleitung) begrenzt. */
    private static long drain(InputStream in) {
        long total = 0;
        byte[] buffer = new byte[4096];
        try (in) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total >= MAX_PROBE_BYTES) {
                    break;
                }
            }
        } catch (IOException ex) {
            return total;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Fehlerabbildung
    // ------------------------------------------------------------------

    private OpsError statusError(int status) {
        return switch (status) {
            case 400 -> error(ErrorCode.SOURCE_PARSE_ERROR,
                    "Jenkins hat die Anfrage als ungueltig abgelehnt (HTTP 400).");
            case 401 -> new OpsError(ErrorCode.AUTH_REQUIRED, instanceId,
                    "Jenkins verlangt eine Anmeldung (HTTP 401). Der konfigurierte Token wurde nicht akzeptiert.",
                    false, "Lokalen Jenkins-API-Token und Benutzernamen pruefen; es erfolgt keine automatische "
                            + "Anmeldung.");
            case 403 -> new OpsError(ErrorCode.FORBIDDEN, instanceId,
                    "Jenkins hat den Lesezugriff verweigert (HTTP 403).", false,
                    "Leseberechtigung der konfigurierten Jenkins-Identitaet pruefen.");
            case 404 -> new OpsError(ErrorCode.NOT_FOUND, instanceId,
                    "Jenkins kennt den angeforderten Job/Build nicht oder er ist nicht sichtbar (HTTP 404).",
                    false, "Jobpfad und Buildnummer in der lokalen Konfiguration pruefen.");
            case 405 -> error(ErrorCode.SOURCE_PARSE_ERROR,
                    "Jenkins hat die HTTP-Methode abgelehnt (HTTP 405).");
            case 407 -> new OpsError(ErrorCode.PROXY_AUTH_REQUIRED, instanceId,
                    "Der konfigurierte HTTP-Proxy verlangt eine Anmeldung (HTTP 407). Ursache ist die "
                            + "Proxy-Authentifizierung, nicht eine fehlende Berechtigung im Cluster; es wird keine "
                            + "TLS-Pruefung abgeschaltet.",
                    false, "Proxy-Zugangsdaten als Secret-Referenz konfigurieren und den Proxy manuell pruefen.");
            case 408 -> new OpsError(ErrorCode.REQUEST_TIMEOUT, instanceId,
                    "Jenkins hat die Anfrage nicht rechtzeitig beantwortet (HTTP 408).", true,
                    "Jenkins-Erreichbarkeit und das Zeitbudget pruefen.");
            case 410 -> new OpsError(ErrorCode.NOT_FOUND, instanceId,
                    "Der angeforderte Jenkins-Build ist nicht mehr vorhanden (HTTP 410).", false,
                    "Eine andere Buildnummer verwenden.");
            case 429 -> new OpsError(ErrorCode.SOURCE_PARSE_ERROR, instanceId,
                    "Jenkins hat wegen zu vieler Aufrufe abgelehnt (HTTP 429).", true,
                    "Spaeter erneut und mit kleinerem Umfang versuchen.");
            case 500, 501, 505 -> error(ErrorCode.SOURCE_PARSE_ERROR,
                    "Jenkins antwortete mit einem Serverfehler (HTTP " + status + "); die Antwort ist nicht "
                            + "auswertbar.");
            case 502, 503, 504 -> new OpsError(ErrorCode.SOURCE_PARSE_ERROR, instanceId,
                    "Jenkins ist ueber sein Gateway nicht erreichbar (HTTP " + status + ").", true,
                    "Erreichbarkeit der Jenkins-Instanz und des Proxys manuell pruefen.");
            default -> status >= 500
                    ? error(ErrorCode.SOURCE_PARSE_ERROR,
                            "Jenkins antwortete mit einem Serverfehler (HTTP " + status + ").")
                    : error(ErrorCode.SOURCE_PARSE_ERROR,
                            "Jenkins hat die Anfrage mit HTTP " + status + " abgelehnt.");
        };
    }

    private OpsError transportError(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return new OpsError(ErrorCode.DNS_FAILURE, instanceId,
                        "Der Jenkins-Hostname konnte nicht aufgeloest werden (DNS).", false,
                        "DNS-Aufloesung und Netzzugang manuell pruefen.");
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new OpsError(ErrorCode.CONNECT_TIMEOUT, instanceId,
                        "Der Verbindungsaufbau zu Jenkins ist fehlgeschlagen oder hat das Zeitlimit "
                                + "ueberschritten.", true,
                        "Erreichbarkeit von Host und Port sowie die Proxykonfiguration pruefen.");
            }
            if (cause instanceof HttpTimeoutException) {
                return new OpsError(ErrorCode.REQUEST_TIMEOUT, instanceId,
                        "Die Jenkins-Anfrage hat das Zeitlimit ueberschritten.", true,
                        "Jenkins-Last und das konfigurierte Zeitbudget pruefen.");
            }
            if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException
                    || cause instanceof CertificateException) {
                return new OpsError(ErrorCode.TLS_UNTRUSTED_CA, instanceId,
                        "Der konfigurierte Jenkins-Endpunkt konnte nicht mit den freigegebenen CA-Zertifikaten "
                                + "geprueft werden.", false,
                        "Die zustaendige CA manuell pruefen und als lokale additionalCaFile konfigurieren.");
            }
            if (cause instanceof SSLException) {
                return new OpsError(ErrorCode.TLS_UNTRUSTED_CA, instanceId,
                        "Der TLS-Aufbau zum Jenkins-Endpunkt ist fehlgeschlagen.", false,
                        "Zertifikat, Hostname und lokale CA-Konfiguration manuell pruefen.");
            }
        }
        return new OpsError(ErrorCode.INTERNAL_ERROR, instanceId,
                "Die Jenkins-Anfrage ist auf Transportebene fehlgeschlagen ("
                        + exception.getClass().getSimpleName() + ").", false,
                "Netzwerk, Proxy und Jenkins-Erreichbarkeit manuell pruefen.");
    }

    private static boolean fatalTransportError(OpsError error) {
        return switch (error.code()) {
            case AUTH_REQUIRED, FORBIDDEN, PROXY_AUTH_REQUIRED, NOT_FOUND, DNS_FAILURE, CONNECT_TIMEOUT,
                    REQUEST_TIMEOUT, TLS_UNTRUSTED_CA, SCOPE_DENIED, CONFIG_INCOMPLETE, CONFIG_INVALID,
                    BUDGET_EXCEEDED -> true;
            default -> false;
        };
    }

    private static SourceStatus statusFor(ErrorCode code) {
        return switch (code) {
            case SCOPE_DENIED, FORBIDDEN, AUTH_REQUIRED, PROXY_AUTH_REQUIRED -> SourceStatus.NOT_ALLOWED;
            case NOT_FOUND -> SourceStatus.NOT_PRESENT;
            case DATA_TRUNCATED -> SourceStatus.TRUNCATED;
            case BUDGET_EXCEEDED -> SourceStatus.NOT_ATTEMPTED;
            default -> SourceStatus.UNREACHABLE;
        };
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private String baseUrl() {
        return definition == null ? null : definition.baseUrl();
    }

    private List<String> allowedParameterNames() {
        return definition == null ? List.of() : definition.allowedParameterNames();
    }

    private OpsError validateTarget(ResolvedTarget target) {
        if (target == null) {
            return error(ErrorCode.INVALID_INPUT, "Es wurde kein aufgeloestes Ziel uebergeben.");
        }
        BuildRef ref = target.buildRef();
        if (ref == null) {
            return error(ErrorCode.CONFIG_INCOMPLETE, "Das Ziel enthaelt keine Buildreferenz.");
        }
        if (!instanceId.equals(ref.instanceId())) {
            return error(ErrorCode.SCOPE_DENIED,
                    "Die Buildreferenz gehoert zu einer anderen Jenkins-Instanz und wird abgewiesen.");
        }
        if (target.budget() == null) {
            return error(ErrorCode.INVALID_INPUT, "Dem Ziel fehlt das auftragsweite Erfassungsbudget.");
        }
        return null;
    }

    private OpsError validateJobPath(List<String> jobPath) {
        if (jobPath == null || jobPath.isEmpty()) {
            return error(ErrorCode.CONFIG_INCOMPLETE,
                    "Es ist kein Jobpfad konfiguriert; ein Job wird nicht geraten.");
        }
        if (definition != null && !definition.allowedJobPaths().isEmpty()
                && !definition.jobPathAllowed(List.copyOf(jobPath))) {
            return error(ErrorCode.SCOPE_DENIED,
                    "Der Jobpfad steht nicht in der konfigurierten Allowlist und wird nicht aufgerufen.");
        }
        return null;
    }

    private SourceResult<BuildSelection> failure(BuildSelection selection, SourceStatus status, Instant at,
            List<String> notes, List<OpsError> errors) {
        return SourceResult.of(instanceId, status, selection, at, notes, errors);
    }

    private SourceResult<BuildSnapshot> metadataFailure(BuildRef ref, SourceStatus status, Instant at,
            List<String> notes, List<OpsError> errors) {
        BuildSnapshot snapshot = new BuildSnapshot(ref, null, null, null, null, null, null, false, List.of(), List.of(),
                List.of(), List.of(), null, false, notes);
        return SourceResult.of(instanceId, status, snapshot, at, notes, errors);
    }

    private SourceResult<ConsoleLogEvidence> consoleFailure(BuildRef ref, SourceStatus status, Instant at,
            List<String> notes, List<OpsError> errors) {
        ConsoleLogEvidence evidence = new ConsoleLogEvidence(ref, List.of(), 1, null, 0, false, "", false, null, null,
                List.of());
        return SourceResult.of(instanceId, status, evidence, at, notes, errors);
    }

    private static BuildSelection placeholderSelection(ResolvedTarget target) {
        if (target != null && target.buildRef() != null) {
            BuildRef ref = target.buildRef();
            return new BuildSelection(ref.instanceId(), ref.jobPath(), ref.buildNumber(), null, null,
                    "Keine Aufloesung moeglich.", false);
        }
        return new BuildSelection("unbekannt", List.of(), 0, null, null, "Keine Aufloesung moeglich.", false);
    }

    private static BuildRef placeholderRef(ResolvedTarget target) {
        if (target != null && target.buildRef() != null) {
            return target.buildRef();
        }
        return new BuildRef("unbekannt", List.of(), 1);
    }

    private OpsError requireJson(Transport transport) {
        String contentType = transport.headers().firstValue("Content-Type").orElse("");
        String body = text(transport);
        if (contentType.toLowerCase(Locale.ROOT).contains("text/html") || JenkinsJsonParser.looksLikeHtml(body)) {
            return error(ErrorCode.SOURCE_PARSE_ERROR,
                    "Jenkins lieferte Login-HTML statt JSON (HTTP 200). Das ist keine gueltige "
                            + "Jenkins-JSON-Antwort.");
        }
        return null;
    }

    private OpsError requirePlainText(Transport transport) {
        String contentType = transport.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return error(ErrorCode.SOURCE_PARSE_ERROR,
                    "Jenkins lieferte Login-HTML statt Console-Text; die Antwort wird nicht ausgewertet.");
        }
        return null;
    }

    private static String text(Transport transport) {
        return new String(transport.body(), StandardCharsets.UTF_8);
    }

    private static String safeHost(URI uri) {
        return uri.getHost() == null ? "<ohne Host>" : uri.getHost();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }

    private OpsError error(ErrorCode code, String message) {
        String scrubbed = scrub(message);
        return new OpsError(code, instanceId, scrubbed, false, suggestedAction(code));
    }

    private OpsError budgetError(String message) {
        return new OpsError(ErrorCode.BUDGET_EXCEEDED, instanceId, scrub(message), false,
                "Das Erfassungsbudget wurde erreicht; der Snapshot bleibt an dieser Stelle unvollstaendig.");
    }

    private OpsError scopeDenied(String message) {
        return new OpsError(ErrorCode.SCOPE_DENIED, instanceId, scrub(message), false,
                "Nur konfigurierte und freigegebene Jenkins-Ziele verwenden.");
    }

    private static String suggestedAction(ErrorCode code) {
        return switch (code) {
            case CONFIG_INCOMPLETE, CONFIG_INVALID -> "Die lokale Jenkins-Konfiguration vervollstaendigen.";
            case SOURCE_PARSE_ERROR -> "Die Jenkins-Antwort manuell pruefen; es wird nichts geraten.";
            default -> "Den Jenkins-Zugriff manuell pruefen.";
        };
    }

    /** Entfernt Secretmarker und - falls bekannt - den eigenen Tokenwert aus einem Text. */
    private String scrub(String message) {
        if (message == null) {
            return "";
        }
        String redacted = Redactor.redact(message).text();
        if (tokenValue != null && tokenValue.length() >= 4 && redacted.contains(tokenValue)) {
            redacted = redacted.replace(tokenValue, Redactor.MASK);
        }
        return redacted;
    }

    private static String authorizationHeader(String username, String token) {
        if (username != null && !username.isBlank()) {
            String raw = username + ":" + token;
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        // API-Token ohne Benutzer: Bearer. Ein leeres Basic-Praefix waere kein gueltiger Vorteil.
        return "Bearer " + token;
    }

    private static String resolveSecret(JenkinsDefinition.SecretRef reference, Function<String, String> environment) {
            if (reference == null) {
            return null;
        }
        if (reference.file() != null) {
            Path file = reference.file();
            if (!Files.isRegularFile(file)) {
                throw new SecretUnavailableException("Die konfigurierte Credential-Datei ist nicht lesbar.");
            }
            String text;
            try {
                long size = Files.size(file);
                if (size > 64 * 1024) {
                    throw new SecretUnavailableException("Die konfigurierte Credential-Datei ist unerwartet gross.");
                }
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new SecretUnavailableException("Die konfigurierte Credential-Datei ist nicht lesbar.");
            }
            for (String line : text.split("\\R")) {
                String candidate = line.trim();
                if (!candidate.isEmpty()) {
                    if (candidate.length() > 4096) {
                        throw new SecretUnavailableException("Der Wert der Credential-Datei ist unerwartet lang.");
                    }
                    return candidate;
                }
            }
            return null;
        }
        if (reference.environmentVar() != null && !reference.environmentVar().isBlank()) {
            String value = environment.apply(reference.environmentVar());
            return value == null ? null : value.trim();
        }
        return null;
    }

    /** Interne Kennzeichnung einer nicht lesbaren Secret-Referenz; die Meldung nennt keinen Wert. */
    private static final class SecretUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SecretUnavailableException(String message) {
            super(message);
        }
    }

    private record BoundedBody(byte[] bytes, boolean capped) {
    }

    private record Transport(int status, java.net.http.HttpHeaders headers, byte[] body, boolean capped,
            long bytesRead, OpsError error) {

        static Transport failure(OpsError error) {
            return new Transport(-1, java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true), new byte[0], false, 0,
                    error);
        }

        boolean failed() {
            return error != null;
        }
    }

    private record SizeProbe(Long totalBytes, long bytesRead, OpsError fatalError, OpsError softError) {
    }

    private record ConsoleLogRead(List<String> lines, int firstLineNumber, Long totalBytes, long bytesRead,
            boolean truncated, String truncatedReason, boolean timestampsAvailable, Long headRequestedFromByte,
            Long tailRequestedFromByte, List<ConsoleLogEvidence.ErrorAnchor> anchors, List<String> notes,
            OpsError fatalError) {

        static ConsoleLogRead failure(OpsError error, Long totalBytes, long bytesRead, List<String> notes) {
            return new ConsoleLogRead(List.of(), -1, totalBytes, bytesRead, false, "", false, null, null, List.of(),
                    notes, error);
        }
    }
}
