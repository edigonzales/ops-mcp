package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.WorkloadDefinition;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerLog;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Lesender OpenShift-Clusteradapter auf Basis fester {@code oc}-Aufrufe.
 *
 * <p>Der Adapter ist strikt lesend und deterministisch:</p>
 * <ol>
 *   <li><b>Scope:</b> Namespace-Allowlist, vollstaendige Clusterkonfiguration und die lokale
 *       kubeconfig werden geprueft, <em>bevor</em> ein {@code oc}-Aufruf startet. Die kubeconfig
 *       muss statisch (Token/lokales Clientzertifikat) sein, den konfigurierten Context
 *       aufloesen und auf den erlaubten API-Server zeigen. {@code exec}-Plugins,
 *       {@code insecure-skip-tls-verify: true} und eigene {@code proxy-url}-Eintraege werden
 *       abgelehnt.</li>
 *   <li><b>Serverbeweis:</b> {@code oc config current-context}, {@code oc whoami --show-server}
 *       und {@code oc version -o json} pruefen den Context bzw. die tatsaechliche API-Adresse,
 *       bevor Inhalte gelesen werden. Bei Abweichung wird abgebrochen.</li>
 *   <li><b>Proxy:</b> Nur {@code NO_PROXY}/{@code no_proxy} der Kindumgebung werden um die
 *       {@code directHosts} des Clusters ergaenzt; bestehende Werte bleiben erhalten. Andere
 *       Proxyvariablen werden weder ueberschrieben noch geloggt.</li>
 *   <li><b>Feste Abfragefolge:</b> Workload, Revisionen (Kandidatenauswahl ueber den
 *       Label-Selektor), Pods, Events in beiden Formaten, danach begrenzte Logs je
 *       ausgewaehltem Container. Vor jedem Aufruf wird {@code budget.tryConsumeRequest()}
 *       geprueft, als Timeout gilt {@code budget.effectiveTimeout(...)}.</li>
 *   <li><b>Keine Schreiboperation:</b> Es wird nichts angelegt, geaendert, geloescht, skaliert,
 *       gestartet oder uebertragen; es werden keine Snapshots und keine Caches geschrieben.</li>
 * </ol>
 *
 * <p>Fehler einzelner Teilabfragen ergeben {@link SourceStatus#TRUNCATED} mit strukturiertem
 * Fehler und erhalten die uebrigen Belege. Ein Totalausfall ergibt
 * {@link SourceStatus#UNREACHABLE}. Ein Fehler ist niemals ein leeres, erfolgreich gelesenes
 * Log.</p>
 */
public final class OcClusterReader implements ClusterReader {

    /** Obergrenze einer JSON-Antwort auf stdout. */
    public static final long JSON_OUTPUT_BYTES = 4L * 1024 * 1024;
    /** Obergrenze der Ausgabe der kleinen Vorabpruefungen. */
    public static final long SMALL_OUTPUT_BYTES = 64L * 1024;
    /** Schluessel der Proxy-Ausnahmeliste. */
    public static final String ENV_NO_PROXY = "NO_PROXY";
    /** Kleingeschriebener Schluessel der Proxy-Ausnahmeliste. */
    public static final String ENV_NO_PROXY_LOWER = "no_proxy";

    private final OpsConfig config;
    private final ProcessRunner runner;
    private final Clock clock;
    private final JsonMapper mapper;
    private final OcJsonParser parser;
    private final Function<ResolvedTarget, InvestigationWindow> windowProvider;
    private final String sourceIdHint;

    /**
     * @param config freigegebene, validierte Konfiguration
     * @param runner Prozessausfuehrung ohne Shell
     * @param clock  injizierte Uhr fuer {@code collectedAt}
     * @param mapper begrenzter JSON-Mapper
     */
    public OcClusterReader(OpsConfig config, ProcessRunner runner, Clock clock, JsonMapper mapper) {
        this(config, runner, clock, mapper, target -> null, null);
    }

    /**
     * Erweiterte Variante.
     *
     * @param windowProvider liefert das Untersuchungsfenster des Ziels, soweit eine Buildzeit
     *                       bekannt ist. Nur daraus entsteht ein {@code --since-time}; ohne
     *                       bekannte Buildzeit wird keine Zeit erfunden.
     * @param sourceIdHint   Kennung fuer {@link #sourceId()}, wenn die Konfiguration mehrere
     *                       Cluster kennt
     */
    public OcClusterReader(OpsConfig config, ProcessRunner runner, Clock clock, JsonMapper mapper,
            Function<ResolvedTarget, InvestigationWindow> windowProvider, String sourceIdHint) {
        this.config = Objects.requireNonNull(config, "config");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.mapper = mapper == null ? YamlSupport.jsonMapper() : mapper;
        this.parser = new OcJsonParser(this.mapper, this.clock);
        this.windowProvider = windowProvider == null ? target -> null : windowProvider;
        this.sourceIdHint = sourceIdHint;
    }

    /**
     * Kennung der Quelle. Da die Schnittstelle keinen Zielparameter hat, wird die Kennung des
     * einzigen konfigurierten Clusters verwendet; bei mehreren Clustern gilt der uebergebene
     * Hinweis. Innerhalb von {@link #collect(ResolvedTarget, CollectionBudget)} gilt immer
     * {@code target.clusterId()}.
     */
    @Override
    public String sourceId() {
        if (sourceIdHint != null && !sourceIdHint.isBlank()) {
            return sourceIdHint;
        }
        if (config.clusters().size() == 1) {
            return config.clusters().keySet().iterator().next();
        }
        return "openshift";
    }

    @Override
    public SourceResult<ClusterCollection> collect(ResolvedTarget target, CollectionBudget budget) {
        String sourceId = target == null || target.clusterId() == null ? sourceId() : target.clusterId();
        Instant collectedAt = clock.instant();
        if (target == null || budget == null || target.definition() == null) {
            return failure(sourceId, ErrorCode.CONFIG_INCOMPLETE,
                    "Es liegt kein aufgeloestes Ziel bzw. kein Erfassungsbudget vor.", collectedAt,
                    target == null || target.definition() == null ? null : target.namespace(), null);
        }
        if (target.isFixture()) {
            // Sicherheitsnetz: Der echte Clusteradapter darf im Fixturemodus nie ein oc starten.
            return failure(sourceId, ErrorCode.CONFIG_INVALID,
                    "Der echte oc-Clusteradapter wird im Fixturemodus nicht verwendet; es wurde kein "
                            + "Live-Zugriff ausgefuehrt.", collectedAt, target.namespace(), requestedKind(target));
        }
        ClusterDefinition cluster = config.cluster(target.clusterId());
        if (cluster == null) {
            return failure(sourceId, ErrorCode.CONFIG_INVALID,
                    "Unbekannte Clusterkennung '" + safe(target.clusterId()) + "'.", collectedAt,
                    target.namespace(), requestedKind(target));
        }
        Collector collector = new Collector(target, budget, cluster, sourceId, collectedAt);
        return collector.run();
    }

    /**
     * Proxy-Ausnahmen der Kindumgebung: bestehende Werte bleiben erhalten, die
     * {@code directHosts} des Clusters werden ergaenzt. Andere Proxyvariablen werden nicht
     * angefasst. Die globale Shellkonfiguration wird nicht veraendert.
     */
    static Map<String, String> proxyEnvironment(ClusterDefinition cluster) {
        if (cluster == null || cluster.directHosts().isEmpty()) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : List.of(ENV_NO_PROXY, ENV_NO_PROXY_LOWER)) {
            String merged = mergeHosts(System.getenv(key), cluster.directHosts());
            if (!merged.isBlank()) {
                environment.put(key, merged);
            }
        }
        return Map.copyOf(environment);
    }

    /** Fuehrt vorhandene Proxy-Ausnahmen und die freigegebenen Direkthosts zusammen. */
    static String mergeHosts(String existing, List<String> directHosts) {
        List<String> merged = new ArrayList<>();
        if (existing != null) {
            for (String part : existing.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !containsIgnoreCase(merged, trimmed)) {
                    merged.add(trimmed);
                }
            }
        }
        if (directHosts != null) {
            for (String host : directHosts) {
                if (host != null && !host.isBlank() && !containsIgnoreCase(merged, host.trim())) {
                    merged.add(host.trim());
                }
            }
        }
        return String.join(",", merged);
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String requestedKind(ResolvedTarget target) {
        return target.definition() == null || target.definition().workload() == null
                || target.definition().workload().kind() == null
                        ? null
                        : target.definition().workload().kind().kubernetesKind();
    }

    private SourceResult<ClusterCollection> failure(String sourceId, ErrorCode code, String message,
            Instant collectedAt, String namespace, String requestedKind) {
        ClusterCollection empty = ClusterCollection.empty(sourceId, namespace, requestedKind);
        List<OpsError> errors = List.of(error(code, sourceId, "openshift", message));
        SourceStatus status = code == ErrorCode.SCOPE_DENIED ? SourceStatus.NOT_ALLOWED
                : code == ErrorCode.CONFIG_INCOMPLETE || code == ErrorCode.CONFIG_INVALID
                        ? SourceStatus.NOT_ATTEMPTED
                        : SourceStatus.UNREACHABLE;
        return new SourceResult<>(sourceId, status, empty, collectedAt, List.of(), errors);
    }

    private static OpsError error(ErrorCode code, String sourceId, String what, String message) {
        return new OpsError(code, sourceId, what + ": " + message, OcFailureMapper.isTransportFailure(code),
                OcFailureMapper.suggestedAction(code));
    }

    private static String safe(String value) {
        return ch.so.agi.ops.security.PathSafety.describe(value);
    }

    /**
     * Zustand einer einzelnen Erfassung. Bewusst eine innere Klasse, damit Vorpruefung,
     * Abfragefolge und Ergebnisbildung ohne versteckten Zustand im Adapter selbst auskommen.
     */
    private final class Collector {

        private final ResolvedTarget target;
        private final CollectionBudget budget;
        private final ClusterDefinition cluster;
        private final String sourceId;
        private final String namespace;
        private final WorkloadDefinition workloadDefinition;
        private final Limits limits;

        private final List<String> notes = new ArrayList<>();
        private final List<String> selectionNotes = new ArrayList<>();
        private final List<OpsError> errors = new ArrayList<>();
        private final List<RevisionSnapshot> revisions = new ArrayList<>();
        private final List<PodSnapshot> pods = new ArrayList<>();
        private final List<EventSnapshot> events = new ArrayList<>();
        private final List<ContainerLog> logs = new ArrayList<>();

        private WorkloadSnapshot workload;
        private OcCommandBuilder oc;
        private Map<String, String> environment = Map.of();
        private String labelSelector;
        private Instant collectedAt;
        private Instant sinceTime;
        private boolean stopped;
        private boolean truncated;

        Collector(ResolvedTarget target, CollectionBudget budget, ClusterDefinition cluster, String sourceId,
                Instant collectedAt) {
            this.target = target;
            this.budget = budget;
            this.cluster = cluster;
            this.sourceId = sourceId;
            this.namespace = target.namespace();
            this.workloadDefinition = target.definition().workload();
            this.limits = config.limits();
            this.collectedAt = collectedAt;
        }

        SourceResult<ClusterCollection> run() {
            try {
                preflight();
                if (!stopped) {
                    readWorkload();
                }
                if (!stopped) {
                    readRevisions();
                }
                if (!stopped) {
                    readPods();
                }
                if (!stopped) {
                    readEvents();
                }
                if (!stopped) {
                    readLogs();
                }
            } catch (OcParseException ex) {
                blocked(ex.code(), describeThrowable(ex), true);
            } catch (RuntimeException ex) {
                blocked(ErrorCode.INTERNAL_ERROR,
                        "Unerwarteter Fehler der Clustererfassung (" + ex.getClass().getSimpleName() + ").", true);
            }
            return result();
        }

        // ----------------------------------------------------------------
        // Vorpruefungen
        // ----------------------------------------------------------------

        private void preflight() {
            if (namespace == null || namespace.isBlank()) {
                blocked(ErrorCode.CONFIG_INCOMPLETE,
                        "Es ist kein Namespace konfiguriert. Ein Defaultnamespace wird nicht geraten.", true);
                return;
            }
            if (!cluster.allowedNamespaces().isEmpty() && !cluster.allowedNamespaces().contains(namespace)) {
                blocked(ErrorCode.SCOPE_DENIED, "Der Namespace '" + safe(namespace)
                        + "' ist fuer Cluster '" + safe(cluster.id()) + "' nicht freigegeben.", true);
                return;
            }
            if (workloadDefinition == null || workloadDefinition.kind() == null
                    || workloadDefinition.name() == null || workloadDefinition.name().isBlank()) {
                blocked(ErrorCode.CONFIG_INCOMPLETE,
                        "Workload-Art oder Workload-Name fehlen; es wird nichts geraten.", true);
                return;
            }
            if (cluster.server() == null || cluster.server().isBlank()) {
                blocked(ErrorCode.CONFIG_INCOMPLETE, "Es ist keine API-Serveradresse konfiguriert.", true);
                return;
            }
            if (cluster.ocExecutable() == null) {
                blocked(ErrorCode.CONFIG_INCOMPLETE, "Es ist kein oc-Programm konfiguriert.", true);
                return;
            }
            KubeconfigGuard.View view;
            try {
                view = KubeconfigGuard.inspect(cluster.kubeconfig(), cluster.context());
            } catch (OcParseException ex) {
                blocked(ex.code(), ex.getMessage(), true);
                return;
            }
            if (!KubeconfigGuard.sameServer(view.server(), cluster.server())) {
                blocked(ErrorCode.SCOPE_DENIED,
                        "Die kubeconfig zeigt auf " + KubeconfigGuard.describeServer(view.server())
                                + ", erlaubt ist " + KubeconfigGuard.describeServer(cluster.server())
                                + ". Es wurde kein oc-Aufruf ausgefuehrt.", true);
                return;
            }
            try {
                oc = OcCommandBuilder.of(cluster);
            } catch (IllegalArgumentException ex) {
                blocked(ErrorCode.CONFIG_INCOMPLETE, ex.getMessage(), true);
                return;
            }
            environment = proxyEnvironment(cluster);
            verifyServer();
        }

        /**
         * Prueft Context und tatsaechliche API-Adresse, bevor Inhalte gelesen werden. Eine
         * Abweichung bricht ab, ohne weitere Abfragen auszufuehren.
         */
        private void verifyServer() {
            if (!consumeRequest("Contextpruefung")) {
                return;
            }
            ExecResult contextResult = exec(oc.configCurrentContext(), SMALL_OUTPUT_BYTES);
            if (contextResult.success()) {
                String currentContext = firstLine(contextResult.stdout());
                if (currentContext != null && !currentContext.equals(cluster.context())) {
                    notes.add("Der aktive kubeconfig-Context '" + safe(currentContext)
                            + "' weicht vom konfigurierten Context '" + safe(cluster.context())
                            + "' ab; alle Aufrufe verwenden ausdruecklich --context.");
                }
            } else {
                notes.add("Der aktive kubeconfig-Context war nicht lesbar ("
                        + OcFailureMapper.classify(contextResult, false).name() + ").");
            }

            if (!consumeRequest("Serverpruefung")) {
                return;
            }
            ExecResult serverResult = exec(oc.whoamiServer(), SMALL_OUTPUT_BYTES);
            if (!serverResult.success()) {
                blocked(OcFailureMapper.classify(serverResult, false), OcFailureMapper.message(serverResult), true);
                return;
            }
            String reported = firstLine(serverResult.stdout());
            if (reported != null && reported.startsWith("http")) {
                if (!KubeconfigGuard.sameServer(reported, cluster.server())) {
                    blocked(ErrorCode.SCOPE_DENIED,
                            "Der von oc gemeldete API-Server " + KubeconfigGuard.describeServer(reported)
                                    + " passt nicht zur erlaubten Adresse "
                                    + KubeconfigGuard.describeServer(cluster.server())
                                    + ". Es wurden keine Inhalte gelesen.", true);
                    return;
                }
            } else {
                notes.add("Die Serveradresse aus 'oc whoami --show-server' war nicht lesbar; massgeblich "
                        + "bleibt die lokale kubeconfig-Pruefung.");
            }

            if (!consumeRequest("Versionspruefung")) {
                return;
            }
            ExecResult versionResult = exec(oc.version(), SMALL_OUTPUT_BYTES);
            if (versionResult.success()) {
                String serverVersion = serverVersionOf(versionResult.stdout());
                if (serverVersion == null) {
                    notes.add("Die Antwort von 'oc version -o json' enthielt keine verwertbare Serverversion.");
                } else {
                    notes.add("API-Serverversion laut 'oc version -o json': " + safe(serverVersion) + ".");
                }
            } else {
                notes.add("'oc version -o json' war nicht lesbar ("
                        + OcFailureMapper.classify(versionResult, false).name() + "); die Erfassung wird "
                        + "fortgesetzt, weil die Serverpruefung bereits erfolgt ist.");
            }
        }

        // ----------------------------------------------------------------
        // Workload, Revisionen, Pods
        // ----------------------------------------------------------------

        private void readWorkload() {
            if (!consumeRequest("Workload")) {
                return;
            }
            boolean deployment = workloadDefinition.kind() == WorkloadDefinition.WorkloadKind.DEPLOYMENT;
            List<String> arguments = deployment
                    ? oc.getDeployment(namespace, workloadDefinition.name())
                    : oc.getDeploymentConfig(namespace, workloadDefinition.name());
            ExecResult result = exec(arguments, JSON_OUTPUT_BYTES);
            if (!result.success()) {
                ErrorCode code = OcFailureMapper.classify(result, false);
                if (deployment && code == ErrorCode.NOT_FOUND) {
                    notes.add("Der Workload '" + safe(workloadDefinition.name())
                            + "' wurde im Namespace '" + safe(namespace)
                            + "' nicht gefunden; NOT_FOUND kann auch 'nicht sichtbar' bedeuten.");
                }
                blocked(code, OcFailureMapper.message(result), true);
                notes.add("Abhaengige Abfragen (Revisionen, Pods, Events, Logs) wurden nicht ausgefuehrt.");
                return;
            }
            recordInput(result);
            try {
                workload = deployment
                        ? parser.parseDeployment(result.stdout(), namespace)
                        : parser.parseDeploymentConfig(result.stdout(), namespace);
            } catch (OcParseException ex) {
                blocked(ex.code(), ex.getMessage(), true);
                return;
            }
            if (workload.uid() == null) {
                notes.add("Die Workloadantwort enthaelt keine metadata.uid; Revisionen und Pods koennen "
                        + "ohne UID nicht belastbar zugeordnet werden.");
            }
            labelSelector = labelSelector(workload);
            if (labelSelector.isBlank()) {
                notes.add("Der Workload nennt keinen Label-Selektor. Revisionen und Pods koennen nicht als "
                        + "Kandidaten ausgewaehlt werden; es wird nichts geraten.");
                selectionNote("Kandidatenauswahl ohne Label-Selektor nicht moeglich.");
                truncated = true;
            } else {
                selectionNote("Kandidatenauswahl ueber den Label-Selektor des Workloads; der Selektor ist "
                        + "kein alleiniger Zugehoerigkeitsbeweis.");
            }
            if (workload.observedGeneration() != null && workload.generation() != null
                    && !workload.observedGeneration().equals(workload.generation())) {
                notes.add("observedGeneration (" + workload.observedGeneration() + ") und generation ("
                        + workload.generation() + ") weichen ab; der Rollout ist nicht abgeschlossen.");
            }
        }

        private void readRevisions() {
            if (labelSelector.isBlank() || !consumeRequest("Revisionen")) {
                return;
            }
            boolean deployment = workloadDefinition.kind() == WorkloadDefinition.WorkloadKind.DEPLOYMENT;
            List<String> arguments = deployment
                    ? oc.getReplicaSets(namespace, labelSelector)
                    : oc.getReplicationControllers(namespace, labelSelector);
            ExecResult result = exec(arguments, JSON_OUTPUT_BYTES);
            if (!result.success()) {
                ErrorCode code = OcFailureMapper.classify(result, false);
                errors.add(error(code, "Revisionen", OcFailureMapper.message(result)));
                notes.add("Revisionen konnten nicht gelesen werden (" + code.name()
                        + "); Pods und Events werden weiterhin erfasst.");
                truncated = true;
                return;
            }
            recordInput(result);
            List<RevisionSnapshot> parsed;
            try {
                parsed = deployment
                        ? parser.parseReplicaSets(result.stdout(), namespace)
                        : parser.parseReplicationControllers(result.stdout(), namespace);
            } catch (OcParseException ex) {
                errors.add(error(ex.code(), "Revisionen", ex.getMessage()));
                notes.add("Die Revisionsantwort war nicht verwertbar; die uebrigen Belege bleiben erhalten.");
                truncated = true;
                return;
            }
            revisions.addAll(parsed);
            selectionNote(parsed.size() + " Revision(en) ueber den Label-Selektor erfasst.");
            if (parsed.size() > 1) {
                String ambiguity = "Mehrere Revisionen (" + parsed.size()
                        + ") gehoeren zum Workload; ohne eindeutige Image-Digest- oder Annotationszuordnung "
                        + "bleibt die Verbindung zum Jenkins-Lauf mehrdeutig.";
                notes.add(ambiguity);
                selectionNote(ambiguity);
            }
            long foreign = parsed.stream().filter(revision -> !ownedByWorkload(revision)).count();
            if (foreign > 0) {
                selectionNote(foreign + " Revision(en) wurden ueber den Label-Selektor erfasst, gehoeren aber "
                        + "nicht nachweislich zur Workload-UID. Sie bleiben als Kandidaten erhalten und werden "
                        + "nicht als zugehoerig behauptet.");
            }
        }

        private void readPods() {
            if (labelSelector.isBlank() || !consumeRequest("Pods")) {
                return;
            }
            List<String> arguments = oc.getPods(namespace, labelSelector);
            ExecResult result = exec(arguments, JSON_OUTPUT_BYTES);
            if (!result.success()) {
                ErrorCode code = OcFailureMapper.classify(result, false);
                errors.add(error(code, "Pods", OcFailureMapper.message(result)));
                notes.add("Pods konnten nicht gelesen werden (" + code.name()
                        + "); Workload und Revisionen bleiben erhalten.");
                truncated = true;
                return;
            }
            recordInput(result);
            int maxPods = effectiveMaxPods();
            List<PodSnapshot> candidates;
            try {
                candidates = parser.parsePods(result.stdout(), namespace, maxPods + 1);
            } catch (OcParseException ex) {
                errors.add(error(ex.code(), "Pods", ex.getMessage()));
                notes.add("Die Podantwort war nicht verwertbar; die uebrigen Belege bleiben erhalten.");
                truncated = true;
                return;
            }
            if (candidates.size() > maxPods) {
                candidates = candidates.subList(0, maxPods);
                String note = "Die Podliste wurde auf " + maxPods + " Pods begrenzt; weitere Kandidaten wurden "
                        + "nicht erfasst.";
                notes.add(note);
                selectionNote(note);
                errors.add(error(ErrorCode.DATA_TRUNCATED, "Pods", note));
                truncated = true;
            }
            int accepted = 0;
            for (PodSnapshot pod : candidates) {
                if (!budget.tryConsumePod()) {
                    String note = "Das auftragsweite Podbudget (" + budget.maxPods()
                            + ") ist erreicht; weitere Pods wurden nicht uebernommen.";
                    notes.add(note);
                    errors.add(error(ErrorCode.BUDGET_EXCEEDED, "Pods", note));
                    truncated = true;
                    break;
                }
                pods.add(pod);
                accepted++;
            }
            selectionNote(accepted + " Pod(s) als Kandidaten erfasst.");
            List<String> duplicates = duplicatePodNames();
            if (!duplicates.isEmpty()) {
                String note = "Gleichnamige Pods mit unterschiedlicher UID erfasst: " + String.join(", ", duplicates)
                        + ". Events werden ueber die UID zugeordnet; die Zuordnung bleibt mehrdeutig.";
                notes.add(note);
                selectionNote(note);
            }
            long foreign = pods.stream().filter(pod -> !ownedByKnownRevision(pod)).count();
            if (foreign > 0) {
                selectionNote(foreign + " Pod(s) gehoeren nicht nachweislich zu einer erfassten Revision "
                        + "(Owner-UID fehlt oder passt nicht). Sie bleiben erfasst und werden nicht als "
                        + "zugehoerig behauptet.");
            }
            for (PodSnapshot pod : pods) {
                List<String> sidecars = OcJsonParser.sidecarCandidates(pod);
                if (!sidecars.isEmpty()) {
                    selectionNote("Pod " + safe(pod.name()) + " hat " + pod.containers().size()
                            + " Container in spec.containers; " + String.join(", ", sidecars)
                            + " sind ohne explizite Kennzeichnung hoechstens moegliche Sidecars. Die "
                            + "Containerklasse bleibt MAIN.");
                }
            }
        }

        // ----------------------------------------------------------------
        // Events
        // ----------------------------------------------------------------

        private void readEvents() {
            int maxEvents = effectiveMaxEvents();
            List<EventSnapshot> core = readEventSource(true, maxEvents);
            List<EventSnapshot> eventsK8sIo = readEventSource(false, maxEvents);
            List<EventSnapshot> merged = parser.mergeEventSources(core, eventsK8sIo);
            long duplicates = merged.stream().filter(EventSnapshot::isDuplicate).count();
            if (duplicates > 0) {
                String note = duplicates + " Event(s) lagen in beiden Formaten vor und wurden als Duplikat "
                        + "markiert; sie zaehlen nicht als unabhaengiger zweiter Beleg.";
                notes.add(note);
                selectionNote(note);
            }
            Set<String> knownUids = knownObjectUids();
            Set<String> knownNames = knownObjectKindNames();
            int dropped = 0;
            for (EventSnapshot event : merged) {
                if (!isRelated(event, knownUids, knownNames)) {
                    dropped++;
                    continue;
                }
                if (events.size() >= maxEvents) {
                    String note = "Die Eventliste wurde auf " + maxEvents + " Eintraege begrenzt.";
                    notes.add(note);
                    errors.add(error(ErrorCode.DATA_TRUNCATED, "Events", note));
                    truncated = true;
                    break;
                }
                if (!budget.tryConsumeEvent()) {
                    String note = "Das auftragsweite Eventbudget (" + budget.maxEvents()
                            + ") ist erreicht; weitere Events wurden nicht uebernommen.";
                    notes.add(note);
                    errors.add(error(ErrorCode.BUDGET_EXCEEDED, "Events", note));
                    truncated = true;
                    break;
                }
                events.add(event);
            }
            if (dropped > 0) {
                selectionNote(dropped + " Event(s) betrafen keines der erfassten Objekte und wurden nicht "
                        + "uebernommen (Zuordnung ueber Objekt-UID, keine Naeherung ueber Zeit oder Namen).");
            }
            selectionNote(events.size() + " Event(s) ueber Objekt-UID zugeordnet.");
        }

        private List<EventSnapshot> readEventSource(boolean core, int maxEvents) {
            String label = core ? "Events (core/v1)" : "Events (events.k8s.io/v1)";
            if (!consumeRequest(label)) {
                return List.of();
            }
            List<String> arguments = core ? oc.getEventsCore(namespace) : oc.getEventsEventsK8sIo(namespace);
            ExecResult result = exec(arguments, JSON_OUTPUT_BYTES);
            if (!result.success()) {
                ErrorCode code = OcFailureMapper.classify(result, false);
                errors.add(error(code, label, OcFailureMapper.message(result)));
                notes.add(label + " konnten nicht gelesen werden (" + code.name()
                        + "); die uebrigen Belege bleiben erhalten.");
                truncated = true;
                return List.of();
            }
            recordInput(result);
            try {
                return core
                        ? parser.parseEventsCore(result.stdout(), maxEvents)
                        : parser.parseEventsEventsK8sIo(result.stdout(), maxEvents);
            } catch (OcParseException ex) {
                errors.add(error(ex.code(), label, ex.getMessage()));
                notes.add(label + " waren nicht verwertbar; die uebrigen Belege bleiben erhalten.");
                truncated = true;
                return List.of();
            }
        }

        // ----------------------------------------------------------------
        // Logs
        // ----------------------------------------------------------------

        private void readLogs() {
            List<String> selected = workloadDefinition.containerNames();
            if (selected.isEmpty() && !workloadDefinition.allWorkloadContainers()) {
                String note = "Keine Containerauswahl konfiguriert (containerNames leer, "
                        + "allWorkloadContainers=false): Containermetadaten wurden erfasst, Logs wurden "
                        + "nicht gelesen.";
                notes.add(note);
                selectionNote(note);
                return;
            }
            if (pods.isEmpty()) {
                notes.add("Es wurden keine Pods erfasst; Container-Logs konnten nicht gelesen werden.");
                return;
            }
            Set<String> wanted = selected.isEmpty()
                    ? new LinkedHashSet<>(allContainerNames())
                    : new LinkedHashSet<>(selected);
            List<String> missing = wanted.stream().filter(name -> !containerExists(name)).toList();
            if (!missing.isEmpty()) {
                String note = "Ausgewaehlte Container kommen in keinem erfassten Pod vor: "
                        + String.join(", ", missing) + ".";
                notes.add(note);
                selectionNote(note);
            }
            if (sinceTime == null) {
                sinceTime = resolveSinceTime();
            }
            if (sinceTime == null) {
                notes.add("Ohne bekannte Buildzeit wurde kein '--since-time' gesetzt; die Logs sind der aktuell "
                        + "verfuegbare Stand und keine historische Zeitreise.");
            }
            for (PodSnapshot pod : pods) {
                for (String container : wanted) {
                    ContainerSnapshot snapshot = containerOf(pod, container);
                    if (snapshot == null) {
                        continue;
                    }
                    readLog(pod.name(), container, false);
                    if (snapshot.restartCount() != null && snapshot.restartCount() > 0) {
                        readLog(pod.name(), container, true);
                    } else if (snapshot.restartCount() != null) {
                        logs.add(ContainerLog.skipped(pod.name(), pod.uid(), container, true,
                                "Restartcount 0; vorherige Logs sind nicht anwendbar."));
                    } else {
                        logs.add(ContainerLog.skipped(pod.name(), pod.uid(), container, true,
                                "Restartcount ist im erfassten Podstatus nicht bekannt; vorherige Logs werden "
                                        + "nicht angefordert."));
                    }
                }
            }
        }

        private void readLog(String podName, String containerName, boolean previous) {
            PodSnapshot pod = pods.stream().filter(candidate -> candidate.name().equals(podName)).findFirst()
                    .orElse(null);
            if (pod == null) {
                return;
            }
            if (!consumeRequest(previous ? "Log (previous) " + containerName : "Log " + containerName)) {
                return;
            }
            long maxBytes = Math.max(1, limits.maxContainerLogBytes());
            List<String> arguments = oc.logs(namespace, podName, containerName, previous, sinceTime, maxBytes);
            ExecResult result = exec(arguments, maxBytes);
            if (result.success()) {
                recordInput(result);
                List<String> lines = redactedLines(result.stdout());
                boolean byteLimited = result.outputTruncated() || result.stdout().length() >= maxBytes;
                String truncationNote = byteLimited
                        ? "Das Log wurde nach dem Bytebudget von " + maxBytes + " Bytes abgeschnitten; das Ende "
                                + "wurde nicht gelesen."
                        : "";
                if (byteLimited) {
                    truncated = true;
                    errors.add(error(ErrorCode.DATA_TRUNCATED, "Log " + containerName, truncationNote));
                }
                logs.add(new ContainerLog(podName, pod.uid(), containerName, previous, true, lines, byteLimited,
                        truncationNote, "", null, "", sinceTime));
                return;
            }
            ErrorCode code = OcFailureMapper.classify(result, previous);
            String message = OcFailureMapper.message(result);
            if (previous && code == ErrorCode.NO_PREVIOUS_LOG) {
                notes.add("Vorherige Logs fuer Container '" + safe(containerName) + "' in Pod '" + safe(podName)
                        + "' sind nicht verfuegbar; ein geloeschter Container wird dadurch nicht "
                        + "wiederhergestellt.");
            } else {
                errors.add(error(code, "Log " + containerName, message));
                truncated = true;
            }
            logs.add(ContainerLog.failed(podName, pod.uid(), containerName, previous, code.name(), message));
        }

        // ----------------------------------------------------------------
        // Hilfsmittel
        // ----------------------------------------------------------------

        private boolean consumeRequest(String what) {
            if (stopped) {
                return false;
            }
            if (!budget.tryConsumeRequest()) {
                String note = "Das auftragsweite Anfragebudget (" + budget.maxRequests() + ") ist erreicht; '"
                        + what + "' wurde nicht ausgefuehrt.";
                notes.add(note);
                errors.add(error(ErrorCode.BUDGET_EXCEEDED, what, note));
                truncated = true;
                stopped = true;
                return false;
            }
            if (budget.remainingTime(clock.instant()).isZero()) {
                String note = "Das auftragsweite Zeitbudget ist erreicht; '" + what + "' wurde nicht ausgefuehrt.";
                notes.add(note);
                errors.add(error(ErrorCode.BUDGET_EXCEEDED, what, note));
                truncated = true;
                stopped = true;
                return false;
            }
            return true;
        }

        private ExecResult exec(List<String> arguments, long maxOutputBytes) {
            Duration timeout = budget.effectiveTimeout(limits.requestTimeout(), clock.instant());
            if (timeout.isZero() || timeout.isNegative()) {
                timeout = Duration.ofMillis(1);
            }
            return runner.exec(cluster.ocExecutable().toString(), arguments, null, environment, timeout,
                    maxOutputBytes);
        }

        private void recordInput(ExecResult result) {
            budget.recordInputBytes(result.stdout() == null ? 0 : result.stdout().length());
            if (budget.inputBudgetExceeded()) {
                String note = "Das auftragsweite Eingangsbudget ist erreicht; weitere Inhalte werden nicht "
                        + "gelesen.";
                notes.add(note);
                errors.add(error(ErrorCode.BUDGET_EXCEEDED, "Eingangsbudget", note));
                truncated = true;
                stopped = true;
            }
        }

        private void blocked(ErrorCode code, String message, boolean fatal) {
            errors.add(error(code, "Clustererfassung", message == null ? code.name() : message));
            truncated = true;
            if (fatal) {
                stopped = true;
            }
        }

        private OpsError error(ErrorCode code, String what, String message) {
            return OcClusterReader.error(code, sourceId, what, message);
        }

        private void selectionNote(String note) {
            if (note != null && !note.isBlank() && !selectionNotes.contains(note)) {
                selectionNotes.add(note);
            }
        }

        private String labelSelector(WorkloadSnapshot snapshot) {
            if (snapshot.labels().isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            snapshot.labels().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
            return String.join(",", parts);
        }

        private boolean ownedByWorkload(RevisionSnapshot revision) {
            if (workload == null || workload.uid() == null) {
                return false;
            }
            return revision.ownerReferences().stream()
                    .anyMatch(owner -> workload.uid().equals(owner.uid()) && revision.kind().equals(owner.kind()));
        }

        private boolean ownedByKnownRevision(PodSnapshot pod) {
            return pod.ownerReferences().stream()
                    .anyMatch(owner -> owner.uid() != null && revisions.stream()
                            .anyMatch(revision -> owner.uid().equals(revision.uid())));
        }

        private List<String> duplicatePodNames() {
            Map<String, Long> byName = new LinkedHashMap<>();
            for (PodSnapshot pod : pods) {
                byName.merge(pod.name(), 1L, Long::sum);
            }
            List<String> duplicates = new ArrayList<>();
            byName.forEach((name, count) -> {
                if (count > 1) {
                    duplicates.add(name + " (" + count + " Instanzen)");
                }
            });
            return duplicates;
        }

        private Set<String> allContainerNames() {
            Set<String> names = new LinkedHashSet<>();
            for (PodSnapshot pod : pods) {
                pod.containers().forEach(container -> names.add(container.name()));
                pod.initContainers().forEach(container -> names.add(container.name()));
            }
            return names;
        }

        private boolean containerExists(String name) {
            return pods.stream().anyMatch(pod -> containerOf(pod, name) != null);
        }

        private ContainerSnapshot containerOf(PodSnapshot pod, String name) {
            ContainerSnapshot main = pod.container(name);
            if (main != null) {
                return main;
            }
            return pod.initContainers().stream().filter(container -> container.name().equals(name)).findFirst()
                    .orElse(null);
        }

        private Set<String> knownObjectUids() {
            Set<String> uids = new LinkedHashSet<>();
            if (workload != null && workload.uid() != null) {
                uids.add(workload.uid());
            }
            revisions.stream().map(RevisionSnapshot::uid).filter(Objects::nonNull).forEach(uids::add);
            pods.stream().map(PodSnapshot::uid).filter(Objects::nonNull).forEach(uids::add);
            return uids;
        }

        private Set<String> knownObjectKindNames() {
            Set<String> names = new LinkedHashSet<>();
            if (workload != null) {
                names.add(workload.kind() + "/" + workload.name());
            }
            revisions.forEach(revision -> names.add(revision.kind() + "/" + revision.name()));
            pods.forEach(pod -> names.add("Pod/" + pod.name()));
            return names;
        }

        private boolean isRelated(EventSnapshot event, Set<String> uids, Set<String> kindNames) {
            if (event.involvedObject() == null) {
                return false;
            }
            if (event.involvedObject().hasUid() && uids.contains(event.involvedObject().uid())) {
                return true;
            }
            return kindNames.contains(event.involvedObject().kind() + "/" + event.involvedObject().name());
        }

        private int effectiveMaxPods() {
            int max = budget.maxPods() > 0 ? budget.maxPods() : limits.maxPods();
            if (limits.maxPods() > 0) {
                max = Math.min(max, limits.maxPods());
            }
            return Math.max(1, max);
        }

        private int effectiveMaxEvents() {
            int max = budget.maxEvents() > 0 ? budget.maxEvents() : limits.maxEvents();
            if (limits.maxEvents() > 0) {
                max = Math.min(max, limits.maxEvents());
            }
            return Math.max(1, max);
        }

        private String serverVersionOf(String stdout) {
            if (stdout == null || stdout.isBlank()) {
                return null;
            }
            try {
                JsonNode root = mapper.readTree(stdout);
                JsonNode serverVersion = root == null ? null : root.get("serverVersion");
                if (serverVersion != null && serverVersion.isObject()) {
                    JsonNode gitVersion = serverVersion.get("gitVersion");
                    if (gitVersion != null && gitVersion.isString()) {
                        return gitVersion.asString();
                    }
                }
                JsonNode openshiftVersion = root == null ? null : root.get("openshiftVersion");
                if (openshiftVersion != null && openshiftVersion.isString()) {
                    return openshiftVersion.asString();
                }
                return null;
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private Instant resolveSinceTime() {
            InvestigationWindow window = windowProvider.apply(target);
            if (window == null || window.from() == null) {
                return null;
            }
            notes.add("'--since-time' stammt aus dem Untersuchungsfenster (" + window.timeBasis().wireName()
                    + "): " + window.from() + ".");
            return window.from();
        }

        private List<String> redactedLines(String text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.add(Redactor.redact(line).text());
            }
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return List.copyOf(lines);
        }

        private String firstLine(String text) {
            if (text == null) {
                return null;
            }
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            return null;
        }

        private String describeThrowable(Throwable throwable) {
            String message = throwable.getMessage();
            return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        }

        SourceResult<ClusterCollection> result() {
            ClusterCollection collection = new ClusterCollection(
                    sourceId,
                    cluster.server(),
                    cluster.context(),
                    namespace,
                    workload,
                    workloadDefinition != null && workloadDefinition.kind() != null
                            ? workloadDefinition.kind().kubernetesKind()
                            : null,
                    List.copyOf(revisions),
                    List.copyOf(pods),
                    List.copyOf(events),
                    List.copyOf(logs),
                    List.copyOf(selectionNotes));
            SourceStatus status = statusFor(collection);
            return new SourceResult<>(sourceId, status, collection, collectedAt, List.copyOf(notes),
                    List.copyOf(errors));
        }

        private SourceStatus statusFor(ClusterCollection collection) {
            boolean anyData = collection.workload() != null || !collection.revisions().isEmpty()
                    || !collection.pods().isEmpty() || !collection.events().isEmpty()
                    || !collection.logs().isEmpty();
            if (!anyData) {
                if (errors.isEmpty()) {
                    return SourceStatus.NOT_PRESENT;
                }
                ErrorCode first = errors.get(0).code();
                if (first == ErrorCode.SCOPE_DENIED || OcFailureMapper.isDenial(first)) {
                    return SourceStatus.NOT_ALLOWED;
                }
                if (first == ErrorCode.CONFIG_INCOMPLETE || first == ErrorCode.CONFIG_INVALID) {
                    return SourceStatus.NOT_ATTEMPTED;
                }
                if (first == ErrorCode.NOT_FOUND || first == ErrorCode.UNSUPPORTED_WORKLOAD) {
                    return SourceStatus.NOT_PRESENT;
                }
                return SourceStatus.UNREACHABLE;
            }
            return errors.isEmpty() && !truncated ? SourceStatus.COLLECTED : SourceStatus.TRUNCATED;
        }
    }
}
