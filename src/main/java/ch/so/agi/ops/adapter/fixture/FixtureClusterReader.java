package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.adapter.openshift.OcJsonParser;
import ch.so.agi.ops.adapter.openshift.OcParseException;
import ch.so.agi.ops.application.OwnershipGraph;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.OpsConfig;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fixtureadapter fuer die Clusterquelle.
 *
 * <p>Der Adapter liest ausschliesslich lokale Dateien des Szenarios und uebergibt die
 * Rohantworten an <em>denselben</em> Parser wie der Live-Adapter
 * ({@link ch.so.agi.ops.adapter.openshift.OcJsonParser}). Es gibt keinen Netzwerkzugriff,
 * keinen Prozessaufruf und keine Shell: Fehlt eine Datei, wird ein Fehlercode gemeldet und
 * niemals eine leere, scheinbar erfolgreiche Liste geliefert.</p>
 *
 * <p>Container-Logs sind Textdateien und werden direkt gelesen. Ein fehlendes vorheriges Log
 * ergibt {@link ErrorCode#NO_PREVIOUS_LOG}; ein fehlendes aktuelles Log
 * {@link ErrorCode#NOT_FOUND}. Beides erscheint als nicht verfuegbares Log mit Fehlercode,
 * nie als leeres erfolgreiches Log.</p>
 */
final class FixtureClusterReader implements ClusterReader {

    private static final String SOURCE_ID = "fixture";

    private final OpsConfig config;
    private final FixtureScenarios fixtures;
    private final JsonMapper mapper;
    private final Clock clock;
    private final OcJsonParser parser;

    FixtureClusterReader(OpsConfig config, FixtureScenarios fixtures, JsonMapper mapper, Clock clock) {
        this.config = config;
        this.fixtures = fixtures;
        this.mapper = mapper;
        this.clock = clock;
        this.parser = OcJsonParserFactory.create(mapper);
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public SourceResult<ClusterCollection> collect(ResolvedTarget target, CollectionBudget budget) {
        Instant collectedAt = clock.instant();
        FixtureData scenario;
        try {
            scenario = fixtures.forTarget(target);
        } catch (RuntimeException ex) {
            return SourceResult.of(SOURCE_ID, SourceStatus.UNREACHABLE,
                    ClusterCollection.empty(clusterId(target), namespace(target), requestedKind(target)),
                    collectedAt, List.of(), List.of(OpsError.of(ErrorCode.CONFIG_INCOMPLETE, SOURCE_ID,
                            "Das Fixture-Szenario ist nicht aufloesbar: "
                                    + ch.so.agi.ops.security.PathSafety.describe(ex.getMessage()))));
        }

        String namespace = namespace(target);
        String requestedKind = requestedKind(target);
        CollectionBudget effective = budget == null
                ? ch.so.agi.ops.domain.CollectionBudget.of(java.time.Duration.ofSeconds(90), collectedAt, 60, 8, 500,
                        Long.MAX_VALUE, 32768)
                : budget;

        List<String> notes = new ArrayList<>();
        List<OpsError> errors = new ArrayList<>();
        List<String> selectionNotes = new ArrayList<>();
        notes.add("Synthetisches Szenario '" + scenario.id() + "': " + scenario.title()
                + ". Die Rohantworten liegen lokal vor; es wurde kein Livezugriff ausgefuehrt.");
        selectionNotes.add("Fixture-Szenario " + scenario.id() + " (synthetisch, Rohantworten aus "
                + "fixture.root).");

        boolean gap = false;

        // --- Workload ---------------------------------------------------------------------
        WorkloadSnapshot workload = null;
        String workloadFile = firstPresent(scenario, "k8s/deployment.json", "k8s/deploymentconfig.json");
        if (workloadFile == null) {
            errors.add(OpsError.of(ErrorCode.NOT_FOUND, SOURCE_ID,
                    "Im Szenario fehlt die Workload-Rohantwort (k8s/deployment.json oder "
                            + "k8s/deploymentconfig.json)."));
            gap = true;
        } else if (forbidden(scenario, workloadFile)) {
            errors.add(forbiddenError(scenario, workloadFile, "Die Workload-Rohantwort ist im Szenario "
                    + "als nicht erlaubt hinterlegt."));
            gap = true;
        } else {
            String json = guardedRead(scenario, workloadFile, errors);
            if (json != null) {
                effective.recordInputBytes(json.length());
                try {
                    workload = "k8s/deploymentconfig.json".equals(workloadFile)
                            ? parser.parseDeploymentConfig(json, namespace)
                            : parser.parseDeployment(json, namespace);
                } catch (OcParseException ex) {
                    errors.add(parseError(workloadFile, ex));
                    gap = true;
                } catch (RuntimeException ex) {
                    errors.add(parseError(workloadFile, ex));
                    gap = true;
                }
            } else {
                gap = true;
            }
        }
        if (workload != null && !requestedKind.isBlank()
                && !requestedKind.equalsIgnoreCase(workload.kind())) {
            selectionNotes.add("Die Konfiguration nennt die Workload-Art " + requestedKind
                    + ", die Rohantwort liefert " + workload.kind()
                    + ". Die Abweichung bleibt sichtbar und wird nicht geglaettet.");
        }

        // --- Revisionen -------------------------------------------------------------------
        List<RevisionSnapshot> revisions = new ArrayList<>();
        String revisionFile = firstPresent(scenario, "k8s/replicasets.json", "k8s/replicationcontrollers.json");
        if (revisionFile == null) {
            errors.add(OpsError.of(ErrorCode.NOT_FOUND, SOURCE_ID,
                    "Im Szenario fehlt die Revisions-Rohantwort (k8s/replicasets.json oder "
                            + "k8s/replicationcontrollers.json)."));
            gap = true;
        } else if (forbidden(scenario, revisionFile)) {
            errors.add(forbiddenError(scenario, revisionFile, "Die Revisions-Rohantwort ist im Szenario "
                    + "als nicht erlaubt hinterlegt."));
            gap = true;
        } else {
            String json = guardedRead(scenario, revisionFile, errors);
            if (json != null) {
                effective.recordInputBytes(json.length());
                try {
                    revisions.addAll("k8s/replicationcontrollers.json".equals(revisionFile)
                            ? parser.parseReplicationControllers(json, namespace)
                            : parser.parseReplicaSets(json, namespace));
                } catch (OcParseException ex) {
                    errors.add(parseError(revisionFile, ex));
                    gap = true;
                } catch (RuntimeException ex) {
                    errors.add(parseError(revisionFile, ex));
                    gap = true;
                }
            } else {
                gap = true;
            }
        }

        OwnershipGraph graph = new OwnershipGraph(new ClusterCollection(clusterId(target), null, null, namespace,
                workload, requestedKind, revisions, List.of(), List.of(), List.of(), List.of()));
        // Zugehoerigkeit ausschliesslich ueber die Owner-UID des Workloads; der Name allein
        // begruendet keine Zugehoerigkeit.
        List<RevisionSnapshot> ownedRevisions = revisionOwnersOf(workload, revisions);
        if (workload != null && ownedRevisions.isEmpty() && !revisions.isEmpty()) {
            selectionNotes.add("Keine Revision traegt die Owner-UID des Workloads " + workload.kind() + "/"
                    + workload.name() + ". Die Revisionsliste wird nicht ersatzweise zugeordnet.");
        }
        OwnershipGraph.RevisionAmbiguity ambiguity = graph.ambiguity(workload);
        if (ambiguity.ambiguous()) {
            selectionNotes.add(ambiguity.reason() + " Kandidaten: "
                    + String.join(", ", ambiguity.candidateUids()) + ".");
        }

        // --- Pods -------------------------------------------------------------------------
        List<PodSnapshot> pods = new ArrayList<>();
        String podsFile = "k8s/pods.json";
        if (!fixtures.exists(scenario, podsFile)) {
            errors.add(OpsError.of(ErrorCode.NOT_FOUND, SOURCE_ID,
                    "Im Szenario fehlt die Pod-Rohantwort (k8s/pods.json)."));
            gap = true;
        } else if (forbidden(scenario, podsFile)) {
            errors.add(forbiddenError(scenario, podsFile, "Die Pod-Rohantwort ist im Szenario als nicht "
                    + "erlaubt hinterlegt."));
            gap = true;
        } else {
            String json = guardedRead(scenario, podsFile, errors);
            if (json != null) {
                effective.recordInputBytes(json.length());
                try {
                    pods.addAll(parser.parsePods(json, namespace, effective.maxPods()));
                } catch (OcParseException ex) {
                    errors.add(parseError(podsFile, ex));
                    gap = true;
                } catch (RuntimeException ex) {
                    errors.add(parseError(podsFile, ex));
                    gap = true;
                }
            } else {
                gap = true;
            }
        }

        Set<String> revisionUids = new LinkedHashSet<>();
        for (RevisionSnapshot revision : ownedRevisions) {
            if (revision.uid() != null) {
                revisionUids.add(revision.uid());
            }
        }
        List<PodSnapshot> ownedPods = new ArrayList<>();
        List<String> unlinkedPods = new ArrayList<>();
        for (PodSnapshot pod : pods) {
            if (belongsToRevisions(pod, revisionUids)) {
                ownedPods.add(pod);
            } else {
                unlinkedPods.add(pod.name() + "@" + pod.uid());
            }
        }
        if (!unlinkedPods.isEmpty()) {
            selectionNotes.add("Nicht ueber die Owner-UID zugeordnete Pods: " + String.join(", ", unlinkedPods)
                    + ". Sie bleiben erfasst, gelten aber nicht als Beleg fuer diesen Workload.");
        }
        if (ownedPods.size() > effective.maxPods()) {
            selectionNotes.add("Podbudget " + effective.maxPods() + " erreicht: "
                    + (ownedPods.size() - effective.maxPods()) + " zugeordnete Pods wurden nicht erfasst.");
            errors.add(OpsError.of(ErrorCode.DATA_TRUNCATED, SOURCE_ID,
                    "Das Podbudget (" + effective.maxPods() + ") wurde erreicht."));
            effective.recordExhaustion("maxPods=" + effective.maxPods() + " erreicht");
            gap = true;
            ownedPods = new ArrayList<>(ownedPods.subList(0, effective.maxPods()));
        }
        List<String> duplicateNames = duplicatePodNames(ownedPods);
        if (!duplicateNames.isEmpty()) {
            selectionNotes.add("Gleichnamige Pods mit unterschiedlicher UID erfasst: "
                    + String.join(", ", duplicateNames)
                    + ". Events werden ueber die Objekt-UID zugeordnet, nicht ueber den Namen.");
        }

        // --- Events -----------------------------------------------------------------------
        List<EventSnapshot> events = new ArrayList<>();
        boolean eventsPresent = false;
        String eventsCore = "k8s/events-core.json";
        String eventsIo = "k8s/events-events-k8s-io.json";
        String eventsForbidden = "k8s/events-forbidden.json";
        if (fixtures.exists(scenario, eventsForbidden)) {
            errors.add(forbiddenError(scenario, eventsForbidden, "Die Eventabfrage ist im Szenario als "
                    + "nicht erlaubt hinterlegt; es werden keine Events erfunden."));
            gap = true;
        } else {
            List<EventSnapshot> core = List.of();
            if (fixtures.exists(scenario, eventsCore)) {
                eventsPresent = true;
                String json = guardedRead(scenario, eventsCore, errors);
                if (json != null) {
                    effective.recordInputBytes(json.length());
                    try {
                        core = parser.parseEventsCore(json, effective.maxEvents());
                    } catch (OcParseException ex) {
                        errors.add(parseError(eventsCore, ex));
                        gap = true;
                    } catch (RuntimeException ex) {
                        errors.add(parseError(eventsCore, ex));
                        gap = true;
                    }
                }
            }
            List<EventSnapshot> typed = List.of();
            if (fixtures.exists(scenario, eventsIo)) {
                eventsPresent = true;
                String json = guardedRead(scenario, eventsIo, errors);
                if (json != null) {
                    effective.recordInputBytes(json.length());
                    try {
                        typed = parser.parseEventsEventsK8sIo(json, effective.maxEvents());
                    } catch (OcParseException ex) {
                        errors.add(parseError(eventsIo, ex));
                        gap = true;
                    } catch (RuntimeException ex) {
                        errors.add(parseError(eventsIo, ex));
                        gap = true;
                    }
                }
            }
            List<EventSnapshot> merged = parser.mergeEventSources(core, typed);
            if (merged != null) {
                events.addAll(merged);
            }
        }
        if (!eventsPresent && errors.stream().noneMatch(error -> error.code() == ErrorCode.FORBIDDEN)) {
            selectionNotes.add("Keine Event-Rohantwort im Szenario. Die fehlende Eventhistorie wird "
                    + "nicht durch eine andere Quelle ersetzt.");
        }

        Set<String> knownUids = new LinkedHashSet<>(revisionUids);
        if (workload != null && workload.uid() != null) {
            knownUids.add(workload.uid());
        }
        for (PodSnapshot pod : ownedPods) {
            if (pod.uid() != null) {
                knownUids.add(pod.uid());
            }
        }
        List<EventSnapshot> filteredEvents = new ArrayList<>();
        int dropped = 0;
        for (EventSnapshot event : events) {
            if (!effective.tryConsumeEvent()) {
                errors.add(OpsError.of(ErrorCode.DATA_TRUNCATED, SOURCE_ID,
                        "Das Eventbudget (" + effective.maxEvents() + ") wurde erreicht."));
                effective.recordExhaustion("maxEvents=" + effective.maxEvents() + " erreicht");
                gap = true;
                break;
            }
            String uid = event.involvedObject() == null ? null : event.involvedObject().uid();
            if (uid != null && knownUids.contains(uid)) {
                filteredEvents.add(event);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            selectionNotes.add(dropped + " Event(s) betrafen Objekte ausserhalb des erfassten "
                    + "Workloads und wurden nicht uebernommen.");
        }

        // --- Container-Logs ---------------------------------------------------------------
        List<ContainerLog> logs = new ArrayList<>();
        Set<String> containerSelection = selectedContainers(target);
        if (!containerSelection.isEmpty()) {
            selectionNotes.add("Containerauswahl aus der Konfiguration: "
                    + String.join(", ", containerSelection) + ".");
        }
        for (PodSnapshot pod : ownedPods) {
            for (ContainerSnapshot container : pod.containers()) {
                if (!containerSelection.isEmpty()
                        && !containerSelection.contains(container.name().toLowerCase(Locale.ROOT))) {
                    logs.add(ContainerLog.skipped(pod.name(), pod.uid(), container.name(), false,
                            "Container ist in der Konfiguration nicht ausgewaehlt."));
                    continue;
                }
                logs.add(readLog(scenario, pod, container, false, collectedAt));
                int restarts = container.restartCount() == null ? 0 : container.restartCount();
                if (restarts > 0) {
                    logs.add(readLog(scenario, pod, container, true, collectedAt));
                } else {
                    logs.add(ContainerLog.skipped(pod.name(), pod.uid(), container.name(), true,
                            "Restartcount 0: es kann kein vorheriger Container existieren."));
                }
            }
        }

        ClusterCollection collection = new ClusterCollection(
                clusterId(target),
                config.cluster(clusterId(target)) == null ? null : config.cluster(clusterId(target)).server(),
                config.cluster(clusterId(target)) == null ? null : config.cluster(clusterId(target)).context(),
                namespace, workload, requestedKind, List.copyOf(revisions), List.copyOf(ownedPods),
                List.copyOf(filteredEvents), List.copyOf(logs), List.copyOf(selectionNotes));

        SourceStatus status = gap ? SourceStatus.TRUNCATED : SourceStatus.COLLECTED;
        return SourceResult.of(SOURCE_ID, status, collection, collectedAt, List.copyOf(notes),
                List.copyOf(errors));
    }

    // --- Logs ------------------------------------------------------------------------------

    private ContainerLog readLog(FixtureData scenario, PodSnapshot pod, ContainerSnapshot container,
            boolean previous, Instant collectedAt) {
        String file = logFile(pod.name(), container.name(), previous);
        if (!fixtures.exists(scenario, file)) {
            if (previous) {
                return ContainerLog.failed(pod.name(), pod.uid(), container.name(), true,
                        ErrorCode.NO_PREVIOUS_LOG.name(),
                        "Die Logdatei des vorherigen Containers fehlt (" + file + "). Ein geloeschter "
                                + "Container wird dadurch nicht wiederhergestellt.");
            }
            return ContainerLog.failed(pod.name(), pod.uid(), container.name(), false, ErrorCode.NOT_FOUND.name(),
                    "Die Logdatei fehlt (" + file + ").");
        }
        String text = fixtures.readRaw(scenario, file);
        if (text == null) {
            return ContainerLog.failed(pod.name(), pod.uid(), container.name(), previous,
                    ErrorCode.SOURCE_PARSE_ERROR.name(), "Die Logdatei ist nicht lesbar (" + file + ").");
        }
        long budget = config.limits().maxContainerLogBytes();
        boolean truncated = text.length() > budget;
        String body = truncated ? text.substring(0, (int) Math.min(budget, text.length())) : text;
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            String redacted = Redactor.redact(line).text();
            if (!redacted.isBlank()) {
                lines.add(redacted);
            }
        }
        String note = truncated
                ? "Das Bytebudget maxContainerLogBytes=" + budget + " wurde erreicht; das Log ist "
                        + "abgeschnitten und nicht vollstaendig gelesen"
                        + (previous ? " (vorheriger Container)" : "") + "."
                : "";
        return new ContainerLog(pod.name(), pod.uid(), container.name(), previous, true, List.copyOf(lines),
                truncated, note, "", null, "", collectedAt);
    }

    private static String logFile(String podName, String containerName, boolean previous) {
        return "k8s/logs/" + podName + "--" + containerName + (previous ? "--previous" : "") + ".log";
    }

    // --- Hilfsmittel ------------------------------------------------------------------------

    private String guardedRead(FixtureData scenario, String relativePath, List<OpsError> errors) {
        try {
            return fixtures.readRaw(scenario, relativePath);
        } catch (RuntimeException ex) {
            errors.add(parseError(relativePath, ex));
            return null;
        }
    }

    private OpsError parseError(String relativePath, RuntimeException ex) {
        return new OpsError(ErrorCode.SOURCE_PARSE_ERROR, SOURCE_ID,
                "Die Rohantwort " + relativePath + " ist nicht auswertbar ("
                        + ch.so.agi.ops.security.PathSafety.describe(ex.getClass().getSimpleName()) + ").",
                false, "Die synthetische Rohantwort im Szenario korrigieren.");
    }

    private boolean forbidden(FixtureData scenario, String relativePath) {
        JsonNode status = statusNode(scenario, relativePath);
        return status != null && status.isNumber() && status.asInt() == 403;
    }

    private OpsError forbiddenError(FixtureData scenario, String relativePath, String message) {
        JsonNode node = fixtures.readRawJson(scenario, relativePath);
        int httpStatus = node != null && node.get("httpStatus") != null && node.get("httpStatus").isNumber()
                ? node.get("httpStatus").asInt()
                : 403;
        ErrorCode code = switch (httpStatus) {
            case 401 -> ErrorCode.AUTH_REQUIRED;
            case 403 -> ErrorCode.FORBIDDEN;
            default -> ErrorCode.FORBIDDEN;
        };
        return new OpsError(code, SOURCE_ID, message + " (HTTP " + httpStatus + ")", false,
                "Die Berechtigung fuer diese Fixturequelle klaeren; es wird kein Ersatzweg verwendet.");
    }

    private JsonNode statusNode(FixtureData scenario, String relativePath) {
        try {
            JsonNode node = fixtures.readRawJson(scenario, relativePath);
            return node == null ? null : node.get("httpStatus");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String firstPresent(FixtureData scenario, String first, String second) {
        if (fixtures.exists(scenario, first)) {
            return first;
        }
        return fixtures.exists(scenario, second) ? second : null;
    }

    /**
     * Revisionen, deren Controller-Owner exakt die UID des Workloads traegt.
     *
     * <p>Bewusst lokal umgesetzt: {@code OwnershipGraph.revisionsOf} vergleicht derzeit das
     * Owner-Kind mit dem Revisions-Kind und findet deshalb keine Revision. Der Fixtureadapter
     * darf davon nicht abhaengen.</p>
     */
    private static List<RevisionSnapshot> revisionOwnersOf(WorkloadSnapshot workload,
            List<RevisionSnapshot> revisions) {
        if (workload == null || workload.uid() == null) {
            return List.of();
        }
        List<RevisionSnapshot> owned = new ArrayList<>();
        for (RevisionSnapshot revision : revisions) {
            boolean matches = revision.ownerReferences().stream().anyMatch(owner ->
                    workload.uid().equals(owner.uid()) && owner.isController());
            if (!matches) {
                // Fallback ohne controller-Flag: UID muss trotzdem exakt passen.
                matches = revision.ownerReferences().stream().anyMatch(owner -> workload.uid().equals(owner.uid()));
            }
            if (matches) {
                owned.add(revision);
            }
        }
        return List.copyOf(owned);
    }

    private static boolean belongsToRevisions(PodSnapshot pod, Set<String> revisionUids) {
        return pod.ownerReferences().stream()
                .anyMatch(owner -> owner.uid() != null && revisionUids.contains(owner.uid()));
    }

    private static List<String> duplicatePodNames(List<PodSnapshot> pods) {
        List<String> duplicates = new ArrayList<>();
        java.util.Map<String, List<PodSnapshot>> grouped = pods.stream()
                .collect(java.util.stream.Collectors.groupingBy(PodSnapshot::name));
        grouped.forEach((name, instances) -> {
            long distinct = instances.stream().map(PodSnapshot::uid).distinct().count();
            if (distinct > 1) {
                duplicates.add(name + " (" + distinct + " UIDs: "
                        + String.join(", ", instances.stream().map(PodSnapshot::uid).map(String::valueOf).toList())
                        + ")");
            }
        });
        duplicates.sort(String::compareTo);
        return List.copyOf(duplicates);
    }

    private static Set<String> selectedContainers(ResolvedTarget target) {
        if (target == null || target.definition() == null || target.definition().workload() == null) {
            return Set.of();
        }
        List<String> names = target.definition().workload().containerNames();
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static String clusterId(ResolvedTarget target) {
        return target == null ? SOURCE_ID : target.clusterId();
    }

    private static String namespace(ResolvedTarget target) {
        return target == null ? "" : String.valueOf(target.namespace());
    }

    private static String requestedKind(ResolvedTarget target) {
        if (target == null || target.definition() == null || target.definition().workload() == null
                || target.definition().workload().kind() == null) {
            return "";
        }
        return target.definition().workload().kind().kubernetesKind();
    }
}
