package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.ObjectRef;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.OwnerReference;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadCondition;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;
import ch.so.agi.ops.security.Redactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wandelt {@code oc}-Rohantworten (JSON) in die normalisierten Domaenenrecords um.
 *
 * <p>Der Parser ist bewusst generisch und seiteneffektfrei: Er liest ausschliesslich die
 * uebergebenen Zeichenketten, schreibt nichts und startet nichts. Dadurch koennen der echte
 * Clusteradapter und der Fixtureadapter dieselben Parserklassen verwenden.</p>
 *
 * <p>Grundsaetze:</p>
 * <ul>
 *   <li>Feld-Allowlist: Nur die in den Domaenenrecords vorhandenen Felder werden uebernommen.
 *       Kubernetes-Secret-Dokumente, {@code .env}-Inhalte und Container-{@code env}-Werte
 *       werden nicht gelesen. Die Domaenenrecords fuehren keine Container-Ports,
 *       -Ressourcen oder -Umgebungswerte; diese Felder werden deshalb nicht uebernommen
 *       (Datenminimierung, keine stille Erweiterung des Domaenenmodells).</li>
 *   <li>Alle Freitexte (Event-Meldungen, Condition-Meldungen, Containerzustandsmeldungen,
 *       Logzeilen) laufen durch {@link Redactor#redact(String)}.</li>
 *   <li>Probe-Header werden nur mit ihren Namen uebernommen; Header-Werte und
 *       {@code exec}-Argumente werden nicht uebernommen ({@code redacted=true}).</li>
 *   <li>Fehlende Zeitangaben bleiben {@code null}. Es werden keine Zeiten erfunden.</li>
 *   <li>Unbekannte oder ungueltige Strukturen ergeben eine {@link OcParseException} mit
 *       {@link ErrorCode#SOURCE_PARSE_ERROR} bzw. {@link ErrorCode#UNSUPPORTED_WORKLOAD};
 *       die Meldung enthaelt keinen Datei- oder Antwortinhalt.</li>
 * </ul>
 *
 * <p>Entscheidung zur Containerklasse: Container aus {@code spec.containers} bleiben immer
 * {@link ContainerSnapshot.ContainerClass#MAIN}, auch wenn ein Pod mehrere davon hat. Init-
 * Container aus {@code initContainerStatuses} sind {@code INIT}. Der Wert {@code SIDECAR}
 * wird nicht vergeben, weil sich ein Sidecar aus dem Manifest nicht sicher ableiten laesst;
 * {@link #sidecarCandidates(PodSnapshot)} nennt hoechstens eine reine Heuristik zur
 * Information.</p>
 */
public final class OcJsonParser {

    /** Gemeinsam nutzbare Instanz mit begrenztem JSON-Mapper und Systemuhr. */
    public static final OcJsonParser DEFAULT = new OcJsonParser();

    /** Annotation der Rollout-Revision eines Deployments. */
    public static final String ANNOTATION_DEPLOYMENT_REVISION = "deployment.kubernetes.io/revision";
    /** Annotation der Revision eines OpenShift-ReplicationControllers. */
    public static final String ANNOTATION_DC_LATEST_VERSION = "openshift.io/deployment-config.latest-version";
    /** Label des Pod-Template-Hashs eines Deployments. */
    public static final String LABEL_POD_TEMPLATE_HASH = "pod-template-hash";

    private static final String FORMAT_CORE = EventSnapshot.FORMAT_CORE;
    private static final String FORMAT_EVENTS_K8S_IO = EventSnapshot.FORMAT_EVENTS_K8S_IO;

    private final JsonMapper mapper;
    private final Clock clock;

    public OcJsonParser() {
        this(YamlSupport.jsonMapper(), Clock.systemUTC());
    }

    public OcJsonParser(JsonMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    public OcJsonParser(JsonMapper mapper, Clock clock) {
        this.mapper = mapper == null ? YamlSupport.jsonMapper() : mapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------
    // Workload
    // ------------------------------------------------------------------

    /** Parst ein Deployment ({@code apps/v1}). */
    public WorkloadSnapshot parseDeployment(String json, String namespace) {
        JsonNode root = root(json, "Deployment");
        requireKind(root, WorkloadSnapshot.KIND_DEPLOYMENT, WorkloadSnapshot.KIND_DEPLOYMENT, namespace);
        return workload(root, namespace, WorkloadSnapshot.KIND_DEPLOYMENT, "apps/v1", true);
    }

    /**
     * Parst eine OpenShift {@code DeploymentConfig} ({@code apps.openshift.io/v1}).
     *
     * @throws OcParseException mit {@link ErrorCode#UNSUPPORTED_WORKLOAD}, wenn der Typ fehlt
     *         oder ein anderer Typ geliefert wurde. Ein nicht unterstuetzter Typ wird erklaert,
     *         nicht simuliert.
     */
    public WorkloadSnapshot parseDeploymentConfig(String json, String namespace) {
        JsonNode root = root(json, "DeploymentConfig");
        requireKind(root, WorkloadSnapshot.KIND_DEPLOYMENT_CONFIG, WorkloadSnapshot.KIND_DEPLOYMENT_CONFIG, namespace);
        return workload(root, namespace, WorkloadSnapshot.KIND_DEPLOYMENT_CONFIG, "apps.openshift.io/v1", false);
    }

    private WorkloadSnapshot workload(JsonNode root, String namespace, String kind, String defaultApiVersion,
            boolean matchLabelsSelector) {
        JsonNode metadata = object(root, "metadata");
        String name = requiredText(metadata, "name", kind);
        JsonNode spec = object(root, "spec");
        JsonNode template = object(spec, "template");
        JsonNode status = object(root, "status");
        JsonNode selectorNode = matchLabelsSelector ? at(root, "spec", "selector", "matchLabels") : at(root, "spec", "selector");
        Map<String, String> selector = stringMap(selectorNode);
        Integer gracePeriod = intValue(object(template, "spec"), "terminationGracePeriodSeconds");
        Long generation = longValue(metadata, "generation");
        Long observedGeneration = longValue(status, "observedGeneration");
        Integer desired = intValue(spec, "replicas");
        Integer updated = intValue(status, "updatedReplicas");
        Map<String, String> annotations = stringMap(object(metadata, "annotations"));
        String revisionHint = matchLabelsSelector
                ? annotations.get(ANNOTATION_DEPLOYMENT_REVISION)
                : firstNonNull(numberOrText(status, "latestVersion"), annotations.get(ANNOTATION_DC_LATEST_VERSION));
        return new WorkloadSnapshot(
                kind,
                firstNonNull(text(root, "apiVersion"), defaultApiVersion),
                firstNonNull(text(metadata, "namespace"), namespace),
                name,
                text(metadata, "uid"),
                generation,
                observedGeneration,
                desired,
                updated,
                intValue(status, "readyReplicas"),
                intValue(status, "availableReplicas"),
                workloadConditions(status),
                updated != null && desired != null ? updated < desired : null,
                instant(metadata, "creationTimestamp"),
                selector,
                text(object(template, "metadata"), "uid"),
                revisionHint,
                preferredProbes(template, gracePeriod),
                text(metadata, "resourceVersion"));
    }

    private List<WorkloadCondition> workloadConditions(JsonNode status) {
        List<WorkloadCondition> result = new ArrayList<>();
        for (JsonNode condition : array(status, "conditions")) {
            result.add(new WorkloadCondition(
                    text(condition, "type"),
                    text(condition, "status"),
                    text(condition, "reason"),
                    redact(text(condition, "message")),
                    instant(condition, "lastTransitionTime"),
                    instant(condition, "lastUpdateTime")));
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------
    // Revisionen
    // ------------------------------------------------------------------

    /** Parst ReplicaSets ({@code ReplicaSetList} oder einzelnes ReplicaSet). */
    public List<RevisionSnapshot> parseReplicaSets(String json, String namespace) {
        JsonNode root = root(json, "ReplicaSet");
        List<RevisionSnapshot> result = new ArrayList<>();
        for (JsonNode item : itemsOf(root)) {
            requireKind(item, RevisionSnapshot.KIND_REPLICA_SET, RevisionSnapshot.KIND_REPLICA_SET, namespace);
            result.add(revision(item, namespace, RevisionSnapshot.KIND_REPLICA_SET, true));
        }
        return List.copyOf(result);
    }

    /** Parst ReplicationControllers ({@code ReplicationControllerList} oder Einzelobjekt). */
    public List<RevisionSnapshot> parseReplicationControllers(String json, String namespace) {
        JsonNode root = root(json, "ReplicationController");
        List<RevisionSnapshot> result = new ArrayList<>();
        for (JsonNode item : itemsOf(root)) {
            requireKind(item, RevisionSnapshot.KIND_REPLICATION_CONTROLLER,
                    RevisionSnapshot.KIND_REPLICATION_CONTROLLER, namespace);
            result.add(revision(item, namespace, RevisionSnapshot.KIND_REPLICATION_CONTROLLER, false));
        }
        return List.copyOf(result);
    }

    private RevisionSnapshot revision(JsonNode item, String namespace, String kind, boolean matchLabelsSelector) {
        JsonNode metadata = object(item, "metadata");
        String name = requiredText(metadata, "name", kind);
        JsonNode template = at(item, "spec", "template");
        Map<String, String> labels = stringMap(object(metadata, "labels"));
        Map<String, String> annotations = stringMap(object(metadata, "annotations"));
        String podTemplateHash = firstNonNull(labels.get(LABEL_POD_TEMPLATE_HASH),
                stringMap(object(object(template, "metadata"), "labels")).get(LABEL_POD_TEMPLATE_HASH));
        JsonNode selectorNode = matchLabelsSelector ? at(item, "spec", "selector", "matchLabels") : at(item, "spec", "selector");
        return new RevisionSnapshot(
                kind,
                firstNonNull(text(metadata, "namespace"), namespace),
                name,
                text(metadata, "uid"),
                ownerReferences(metadata),
                intValue(object(item, "spec"), "replicas"),
                intValue(object(item, "status"), "readyReplicas"),
                intValue(object(item, "status"), "availableReplicas"),
                instant(metadata, "creationTimestamp"),
                integerAnnotation(annotations, matchLabelsSelector
                        ? ANNOTATION_DEPLOYMENT_REVISION
                        : ANNOTATION_DC_LATEST_VERSION),
                podTemplateHash,
                containerImages(template).values().stream().toList(),
                containerImages(template),
                probesOfTemplate(template),
                text(object(template, "metadata"), "uid"),
                labels,
                stringMap(selectorNode));
    }

    // ------------------------------------------------------------------
    // Pods
    // ------------------------------------------------------------------

    /**
     * Parst Pods ({@code PodList} oder Einzelobjekt) und begrenzt die Anzahl.
     *
     * @param maxPods Obergrenze; {@code <= 0} bedeutet keine zusaetzliche Begrenzung
     */
    public List<PodSnapshot> parsePods(String json, String namespace, int maxPods) {
        JsonNode root = root(json, "Pod");
        List<PodSnapshot> result = new ArrayList<>();
        for (JsonNode item : itemsOf(root)) {
            if (maxPods > 0 && result.size() >= maxPods) {
                break;
            }
            requireKindIfPresent(item, "Pod", namespace);
            result.add(pod(item, namespace));
        }
        return List.copyOf(result);
    }

    /** Parst einen einzelnen Pod. */
    public PodSnapshot parsePod(String json, String namespace) {
        JsonNode root = root(json, "Pod");
        List<JsonNode> items = itemsOf(root);
        if (items.isEmpty()) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR, "Pod-Antwort ohne Objekt.");
        }
        JsonNode item = items.get(0);
        requireKindIfPresent(item, "Pod", namespace);
        return pod(item, namespace);
    }

    private PodSnapshot pod(JsonNode item, String namespace) {
        JsonNode metadata = object(item, "metadata");
        JsonNode spec = object(item, "spec");
        JsonNode status = object(item, "status");
        String name = requiredText(metadata, "name", "Pod");
        Map<String, String> labels = stringMap(object(metadata, "labels"));
        Map<String, String> annotations = stringMap(object(metadata, "annotations"));
        Integer gracePeriod = intValue(spec, "terminationGracePeriodSeconds");
        List<ContainerSnapshot> containers = containerSnapshots(spec, status, "containers", "containerStatuses",
                ContainerSnapshot.ContainerClass.MAIN);
        List<ContainerSnapshot> initContainers = containerSnapshots(spec, status, "initContainers",
                "initContainerStatuses", ContainerSnapshot.ContainerClass.INIT);
        List<ProbeConfig> probes = new ArrayList<>(probesOf(spec, "containers", gracePeriod));
        probes.addAll(probesOf(spec, "initContainers", gracePeriod));
        String revisionHint = firstNonNull(labels.get(LABEL_POD_TEMPLATE_HASH),
                annotations.get(ANNOTATION_DC_LATEST_VERSION));
        return new PodSnapshot(
                firstNonNull(text(metadata, "namespace"), namespace),
                name,
                text(metadata, "uid"),
                text(status, "phase"),
                text(spec, "nodeName"),
                instant(metadata, "creationTimestamp"),
                instant(metadata, "deletionTimestamp"),
                podConditions(status),
                ownerReferences(metadata),
                containers,
                initContainers,
                List.copyOf(probes),
                text(at(spec, "template", "metadata"), "uid"),
                revisionHint,
                text(spec, "restartPolicy"),
                labels);
    }

    private List<PodSnapshot.PodCondition> podConditions(JsonNode status) {
        List<PodSnapshot.PodCondition> result = new ArrayList<>();
        for (JsonNode condition : array(status, "conditions")) {
            result.add(new PodSnapshot.PodCondition(
                    text(condition, "type"),
                    text(condition, "status"),
                    text(condition, "reason"),
                    redact(text(condition, "message")),
                    instant(condition, "lastTransitionTime")));
        }
        return List.copyOf(result);
    }

    private List<ContainerSnapshot> containerSnapshots(JsonNode spec, JsonNode status, String specField,
            String statusField, ContainerSnapshot.ContainerClass containerClass) {
        Map<String, JsonNode> statuses = byName(array(status, statusField));
        List<ContainerSnapshot> result = new ArrayList<>();
        for (JsonNode container : array(spec, specField)) {
            String name = requiredText(container, "name", "Container");
            result.add(containerSnapshot(name, container, statuses.get(name), containerClass));
        }
        return List.copyOf(result);
    }

    private ContainerSnapshot containerSnapshot(String name, JsonNode specContainer, JsonNode statusNode,
            ContainerSnapshot.ContainerClass containerClass) {
        ContainerStatePair states = states(statusNode);
        Instant startedAt = states.current() == null ? null : states.current().startedAt();
        Instant finishedAt = states.last() == null ? null : states.last().finishedAt();
        if (finishedAt == null && states.current() != null) {
            finishedAt = states.current().finishedAt();
        }
        return new ContainerSnapshot(
                name,
                containerClass,
                firstNonNull(text(statusNode, "image"), text(specContainer, "image")),
                text(statusNode, "imageID"),
                boolValue(statusNode, "ready"),
                intValue(statusNode, "restartCount"),
                states.current(),
                states.last(),
                boolValue(statusNode, "started"),
                startedAt,
                finishedAt);
    }

    private record ContainerStatePair(ContainerSnapshot.ContainerState current,
            ContainerSnapshot.ContainerState last) {
    }

    private static ContainerStatePair states(JsonNode statusNode) {
        return new ContainerStatePair(containerState(object(statusNode, "state")),
                containerState(object(statusNode, "lastState")));
    }

    private static ContainerSnapshot.ContainerState containerState(JsonNode stateNode) {
        if (stateNode == null) {
            return null;
        }
        JsonNode waiting = object(stateNode, "waiting");
        if (waiting != null) {
            return new ContainerSnapshot.ContainerState(ContainerSnapshot.ContainerState.StateKind.WAITING,
                    text(waiting, "reason"), redact(text(waiting, "message")), null, null, null, null);
        }
        JsonNode running = object(stateNode, "running");
        if (running != null) {
            return new ContainerSnapshot.ContainerState(ContainerSnapshot.ContainerState.StateKind.RUNNING,
                    null, null, null, null, instant(running, "startedAt"), null);
        }
        JsonNode terminated = object(stateNode, "terminated");
        if (terminated != null) {
            return new ContainerSnapshot.ContainerState(ContainerSnapshot.ContainerState.StateKind.TERMINATED,
                    text(terminated, "reason"), redact(text(terminated, "message")),
                    intValue(terminated, "exitCode"), intValue(terminated, "signal"),
                    instant(terminated, "startedAt"), instant(terminated, "finishedAt"));
        }
        return null;
    }

    /**
     * Reine Heuristik zur Information: Bei mehr als einem Container in {@code spec.containers}
     * sind alle Container nach dem ersten <em>moegliche</em> Sidecars. Die Containerklasse
     * bleibt ausdruecklich {@code MAIN}; aus dem Manifest laesst sich ein Sidecar nicht sicher
     * ableiten.
     */
    public static List<String> sidecarCandidates(PodSnapshot pod) {
        if (pod == null || pod.containers().size() <= 1) {
            return List.of();
        }
        return pod.containers().subList(1, pod.containers().size()).stream()
                .map(ContainerSnapshot::name)
                .toList();
    }

    // ------------------------------------------------------------------
    // Probes
    // ------------------------------------------------------------------

    private List<ProbeConfig> probesOfTemplate(JsonNode template) {
        Integer gracePeriod = intValue(object(template, "spec"), "terminationGracePeriodSeconds");
        List<ProbeConfig> result = new ArrayList<>(probesOf(object(template, "spec"), "containers", gracePeriod));
        result.addAll(probesOf(object(template, "spec"), "initContainers", gracePeriod));
        return List.copyOf(result);
    }

    /**
     * Eine Map kann nur eine Probe je Container fuehren. Prioritaet: Liveness, dann Readiness,
     * dann Startup. Alle Probes eines Containers stehen in {@link RevisionSnapshot#probes()}
     * und {@link PodSnapshot#probesFromPod()}.
     */
    private Map<String, ProbeConfig> preferredProbes(JsonNode template, Integer gracePeriod) {
        Map<String, ProbeConfig> result = new LinkedHashMap<>();
        for (JsonNode container : array(object(template, "spec"), "containers")) {
            String name = text(container, "name");
            if (name == null) {
                continue;
            }
            List<ProbeConfig> probes = probesOfContainer(container, name, gracePeriod);
            for (ProbeConfig.ProbeKind kind : List.of(ProbeConfig.ProbeKind.LIVENESS,
                    ProbeConfig.ProbeKind.READINESS, ProbeConfig.ProbeKind.STARTUP)) {
                ProbeConfig match = probes.stream().filter(probe -> probe.kind() == kind).findFirst().orElse(null);
                if (match != null) {
                    result.put(name, match);
                    break;
                }
            }
        }
        return Map.copyOf(result);
    }

    private List<ProbeConfig> probesOf(JsonNode spec, String containersField, Integer gracePeriod) {
        List<ProbeConfig> result = new ArrayList<>();
        for (JsonNode container : array(spec, containersField)) {
            String name = text(container, "name");
            if (name == null) {
                continue;
            }
            result.addAll(probesOfContainer(container, name, gracePeriod));
        }
        return List.copyOf(result);
    }

    private List<ProbeConfig> probesOfContainer(JsonNode container, String containerName, Integer gracePeriod) {
        List<ProbeConfig> result = new ArrayList<>();
        addProbe(result, container, containerName, "livenessProbe", ProbeConfig.ProbeKind.LIVENESS, gracePeriod);
        addProbe(result, container, containerName, "readinessProbe", ProbeConfig.ProbeKind.READINESS, gracePeriod);
        addProbe(result, container, containerName, "startupProbe", ProbeConfig.ProbeKind.STARTUP, gracePeriod);
        return List.copyOf(result);
    }

    private void addProbe(List<ProbeConfig> target, JsonNode container, String containerName, String field,
            ProbeConfig.ProbeKind kind, Integer gracePeriod) {
        JsonNode probe = object(container, field);
        if (probe == null) {
            return;
        }
        ProbeConfig.HandlerKind handlerKind = ProbeConfig.HandlerKind.UNKNOWN;
        String httpPath = null;
        String httpPort = null;
        String httpScheme = null;
        List<String> headerNames = new ArrayList<>();
        boolean execCommandRedacted = false;
        boolean redacted = false;
        StringBuilder note = new StringBuilder();

        JsonNode httpGet = object(probe, "httpGet");
        JsonNode tcpSocket = object(probe, "tcpSocket");
        JsonNode exec = object(probe, "exec");
        if (httpGet != null) {
            handlerKind = ProbeConfig.HandlerKind.HTTP_GET;
            httpPath = text(httpGet, "path");
            httpPort = portText(httpGet.get("port"));
            // Kubernetes wertet ein fehlendes scheme als HTTP; der Default wird hier
            // explizit gesetzt, damit der Konfigurationsvergleich denselben Wert sieht.
            httpScheme = firstNonNull(text(httpGet, "scheme"), "HTTP");
            List<JsonNode> headers = array(httpGet, "httpHeaders");
            if (!headers.isEmpty()) {
                for (JsonNode header : headers) {
                    String headerName = text(header, "name");
                    if (headerName != null && !headerNames.contains(headerName)) {
                        headerNames.add(headerName);
                    }
                }
                redacted = true;
                note.append("HTTP-Header-Werte werden nicht uebernommen (koennen Zugangsdaten enthalten). ");
            }
        } else if (tcpSocket != null) {
            handlerKind = ProbeConfig.HandlerKind.TCP_SOCKET;
            httpPort = portText(tcpSocket.get("port"));
        } else if (exec != null) {
            handlerKind = ProbeConfig.HandlerKind.EXEC;
            execCommandRedacted = true;
            redacted = true;
            note.append("exec-Argumente der Probe werden nicht uebernommen. ");
        }
        target.add(new ProbeConfig(
                containerName,
                kind,
                handlerKind,
                httpPath,
                httpPort,
                httpScheme,
                List.copyOf(headerNames),
                execCommandRedacted,
                intValue(probe, "initialDelaySeconds"),
                intValue(probe, "periodSeconds"),
                intValue(probe, "timeoutSeconds"),
                intValue(probe, "successThreshold"),
                intValue(probe, "failureThreshold"),
                gracePeriod,
                redacted,
                note.toString().trim()));
    }

    private static String portText(JsonNode portNode) {
        if (portNode == null || portNode.isNull()) {
            return null;
        }
        if (portNode.isNumber()) {
            return Integer.toString(portNode.asInt());
        }
        if (portNode.isString()) {
            String value = portNode.asString();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /** Parst Events im Format {@code core/v1}. */
    public List<EventSnapshot> parseEventsCore(String json, int maxEvents) {
        return parseEvents(json, maxEvents, FORMAT_CORE);
    }

    /** Parst Events im Format {@code events.k8s.io/v1}. */
    public List<EventSnapshot> parseEventsEventsK8sIo(String json, int maxEvents) {
        return parseEvents(json, maxEvents, FORMAT_EVENTS_K8S_IO);
    }

    private List<EventSnapshot> parseEvents(String json, int maxEvents, String defaultFormat) {
        JsonNode root = root(json, "Event");
        List<EventSnapshot> result = new ArrayList<>();
        for (JsonNode item : itemsOf(root)) {
            if (maxEvents > 0 && result.size() >= maxEvents) {
                break;
            }
            result.add(event(item, formatOf(item, defaultFormat)));
        }
        return List.copyOf(result);
    }

    private EventSnapshot event(JsonNode item, String format) {
        String itemFormat = formatOf(item, format);
        boolean eventsFormat = FORMAT_EVENTS_K8S_IO.equals(itemFormat);
        JsonNode metadata = object(item, "metadata");
        JsonNode involved = object(item, "involvedObject");
        if (involved == null) {
            involved = object(item, "regarding");
        }
        ObjectRef involvedObject = null;
        if (involved != null) {
            String kind = text(involved, "kind");
            if (kind == null) {
                throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR,
                        "Event ohne involvedObject.kind bzw. regarding.kind.");
            }
            involvedObject = new ObjectRef(kind, text(involved, "namespace"), text(involved, "name"),
                    text(involved, "uid"));
        }
        JsonNode series = object(item, "series");
        Integer count = eventsFormat
                ? firstNonNull(intValue(series, "count"), intValue(item, "deprecatedCount"))
                : intValue(item, "count");
        Instant eventTime = eventsFormat ? instant(item, "eventTime") : null;
        Instant firstTimestamp = eventsFormat
                ? instant(item, "deprecatedFirstTimestamp")
                : instant(item, "firstTimestamp");
        Instant lastTimestamp = eventsFormat
                ? firstNonNull(instant(series, "lastObservedTime"), instant(item, "deprecatedLastTimestamp"))
                : instant(item, "lastTimestamp");
        String reporting = eventsFormat
                ? firstNonNull(text(item, "reportingController"), text(item, "reportingInstance"))
                : text(object(item, "source"), "component");
        String namespace = firstNonNull(text(metadata, "namespace"), text(involved, "namespace"));
        return new EventSnapshot(
                involvedObject,
                text(item, "reason"),
                // events.k8s.io/v1 nennt die Meldung 'note'; ohne diesen Fallback waere die
                // Meldung im zweiten Format leer und die Deduplizierung ueber den
                // dedupeKey() wuerde dasselbe Event nicht erkennen.
                redact(eventsFormat
                        ? firstNonNull(text(item, "note"), text(item, "message"))
                        : text(item, "message")),
                text(item, "type"),
                reporting,
                eventTime,
                firstTimestamp,
                lastTimestamp,
                count,
                itemFormat,
                null,
                clock.instant(),
                text(metadata, "uid"),
                sourcePath(itemFormat, namespace));
    }

    /**
     * Fuehrt die Events beider Formate zusammen. Das zweite Vorkommen desselben Events
     * (gleicher {@link EventSnapshot#dedupeKey()}) erhaelt {@code duplicateOfFormat} und wird
     * damit nicht als unabhaengiger zweiter Beleg gezaehlt.
     */
    public List<EventSnapshot> mergeEventSources(List<EventSnapshot> core, List<EventSnapshot> eventsK8sIo) {
        List<EventSnapshot> merged = new ArrayList<>();
        Map<String, String> formatsByKey = new LinkedHashMap<>();
        List<EventSnapshot> all = new ArrayList<>();
        if (core != null) {
            all.addAll(core);
        }
        if (eventsK8sIo != null) {
            all.addAll(eventsK8sIo);
        }
        for (EventSnapshot event : all) {
            String key = event.dedupeKey();
            if (key.replace("|", "").isBlank()) {
                // Ohne UID, Kind, Name, Reason, Meldung und Zaehler ist keine belastbare
                // Gleichheit belegbar; solche Eintraege werden nicht zusammengelegt.
                merged.add(event);
                continue;
            }
            String seenFormat = formatsByKey.get(key);
            if (seenFormat == null) {
                formatsByKey.put(key, event.eventFormat());
                merged.add(event);
            } else {
                merged.add(withDuplicateOfFormat(event, seenFormat));
            }
        }
        return List.copyOf(merged);
    }

    private static EventSnapshot withDuplicateOfFormat(EventSnapshot event, String duplicateOfFormat) {
        return new EventSnapshot(event.involvedObject(), event.reason(), event.message(), event.type(),
                event.reportingComponent(), event.eventTime(), event.firstTimestamp(), event.lastTimestamp(),
                event.count(), event.eventFormat(), duplicateOfFormat, event.observedAt(), event.uid(),
                event.sourcePath());
    }

    private static String formatOf(JsonNode item, String defaultFormat) {
        String apiVersion = text(item, "apiVersion");
        if (apiVersion != null && apiVersion.startsWith("events.k8s.io")) {
            return FORMAT_EVENTS_K8S_IO;
        }
        if (object(item, "regarding") != null && object(item, "involvedObject") == null) {
            return FORMAT_EVENTS_K8S_IO;
        }
        return defaultFormat;
    }

    private static String sourcePath(String format, String namespace) {
        boolean eventsFormat = FORMAT_EVENTS_K8S_IO.equals(format);
        String prefix = eventsFormat ? "/apis/events.k8s.io/v1" : "/api/v1";
        if (namespace == null || namespace.isBlank()) {
            return prefix + "/events";
        }
        return prefix + "/namespaces/" + namespace + "/events";
    }

    // ------------------------------------------------------------------
    // Gemeinsame Hilfsmittel
    // ------------------------------------------------------------------

    private JsonNode root(String json, String what) {
        if (json == null || json.isBlank()) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR, what + "-Antwort ist leer.");
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    what + "-Antwort ist kein gueltiges JSON.", ex);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR, what + "-Antwort ist kein JSON-Objekt.");
        }
        return parsed;
    }

    private static List<JsonNode> itemsOf(JsonNode root) {
        JsonNode items = root.get("items");
        if (items != null && items.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode item : items) {
                if (item != null && item.isObject()) {
                    result.add(item);
                }
            }
            return List.copyOf(result);
        }
        return List.of(root);
    }

    private static void requireKind(JsonNode node, String expected, String what, String namespace) {
        String kind = text(node, "kind");
        if (kind == null || !expected.equals(kind)) {
            throw new OcParseException(ErrorCode.UNSUPPORTED_WORKLOAD,
                    "Erwartet wurde " + expected + " in Namespace '" + safe(namespace) + "', geliefert wurde "
                            + (kind == null ? "kein Typ" : "'" + safe(kind) + "'") + ". "
                            + "Nicht unterstuetzte Workload-Arten werden nicht simuliert.");
        }
    }

    private static void requireKindIfPresent(JsonNode node, String expected, String namespace) {
        String kind = text(node, "kind");
        if (kind != null && !expected.equals(kind)) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "In Namespace '" + safe(namespace) + "' wurde " + expected + " erwartet, geliefert wurde '"
                            + safe(kind) + "'.");
        }
    }

    private static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null && value.isObject() ? value : null;
    }

    private static JsonNode at(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) {
            current = child(current, field);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static List<JsonNode> array(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.isObject()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, JsonNode> byName(List<JsonNode> nodes) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String name = text(node, "name");
            if (name != null) {
                result.put(name, node);
            }
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static String requiredText(JsonNode node, String field, String what) {
        String value = text(node, field);
        if (value == null) {
            throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    what + "-Antwort ohne " + field + ".");
        }
        return value;
    }

    /** Zahl oder Zeichenkette als Text; Kubernetes liefert Revisionsnummern je nach Feld gemischt. */
    private static String numberOrText(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asString();
        }
        if (value.isString()) {
            String text = value.asString();
            return text.isBlank() ? null : text;
        }
        return null;
    }

    private static Integer intValue(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private static Boolean boolValue(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            // Nicht verwertbare Zeitangaben werden nicht erfunden und nicht ersetzt.
            return null;
        }
    }

    private static Integer integerAnnotation(Map<String, String> annotations, String key) {
        String value = annotations.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : node.propertyNames()) {
            JsonNode value = node.get(name);
            if (value != null && value.isString()) {
                result.put(name, value.asString());
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, String> containerImages(JsonNode template) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode container : array(object(template, "spec"), "containers")) {
            String name = text(container, "name");
            String image = text(container, "image");
            if (name != null && image != null) {
                result.put(name, image);
            }
        }
        return Map.copyOf(result);
    }

    private static List<OwnerReference> ownerReferences(JsonNode metadata) {
        List<OwnerReference> result = new ArrayList<>();
        for (JsonNode owner : array(metadata, "ownerReferences")) {
            String kind = text(owner, "kind");
            if (kind == null) {
                throw new OcParseException(ErrorCode.SOURCE_PARSE_ERROR,
                        "Owner-Referenz ohne Kind. Die Zugehoerigkeit wird nicht geraten.");
            }
            result.add(new OwnerReference(
                    text(owner, "apiVersion"),
                    kind,
                    text(owner, "name"),
                    text(owner, "uid"),
                    boolValue(owner, "controller"),
                    boolValue(owner, "blockOwnerDeletion")));
        }
        return List.copyOf(result);
    }

    private static String redact(String value) {
        return value == null ? null : Redactor.redact(value).text();
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String safe(String value) {
        return ch.so.agi.ops.security.PathSafety.describe(value);
    }
}
