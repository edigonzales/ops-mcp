package ch.so.agi.ops.application;

import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.ConfigValue;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.ContainerLog;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.application.port.SnapshotStore;
import ch.so.agi.ops.evidence.JsonLineRenderer;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.snapshot.SnapshotDraft;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.SnapshotLink;
import ch.so.agi.ops.snapshot.SourceSummary;
import ch.so.agi.ops.util.VersionInfo;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Koordiniert die Erfassung eines Untersuchungsauftrags.
 *
 * <p>Reihenfolge und Robustheit:</p>
 * <ol>
 *   <li>Jenkins-Metadaten und Console-Log werden zuerst gelesen, weil sie die Zeitbasis und
 *       die Revisionsquelle liefern.</li>
 *   <li>Die OpenShift-Erfassung erfolgt zeitnah, solange der aktuelle Clusterzustand noch
 *       dem Lauf zugeordnet werden kann.</li>
 *   <li>Die Repositorylektuere wartet bei Bedarf auf eine belastbare Revision. Fehlt sie,
 *       wird {@code REVISION_UNKNOWN}/{@code REVISION_UNAVAILABLE} gemeldet und nicht
 *       stillschweigend {@code HEAD} verwendet.</li>
 * </ol>
 *
 * <p>Ein Jenkins-Ausfall loescht vorhandene Clusterbelege nicht. Fehlende Links bleiben
 * markiert.</p>
 */
public final class InvestigationService {

    private final OpsConfig config;
    private final ClockProvider clock;
    private final SnapshotStore store;
    private final ClusterReader clusterReader;
    private final Function<String, BuildReader> buildReaders;
    private final ConfigurationReader configurationReader;
    private final JsonMapper mapper;
    private final JsonLineRenderer renderer;
    private final VersionInfo versionInfo;

    public InvestigationService(OpsConfig config, ClockProvider clock, SnapshotStore store,
            ClusterReader clusterReader, Function<String, BuildReader> buildReaders,
            ConfigurationReader configurationReader, JsonMapper mapper, VersionInfo versionInfo) {
        this.config = config;
        this.clock = clock;
        this.store = store;
        this.clusterReader = clusterReader;
        this.buildReaders = buildReaders;
        this.configurationReader = configurationReader;
        this.mapper = mapper;
        this.renderer = new JsonLineRenderer(mapper);
        this.versionInfo = versionInfo;
    }

    /**
     * Vergibt die Evidence-IDs deterministisch in Erfassungsreihenfolge. Der Store behaelt
     * bereits vergebene, wohlgeformte IDs unveraendert bei, damit Werkzeug- und CLI-Ausgaben
     * dieselben IDs nennen wie das Manifest.
     */
    private final java.util.concurrent.atomic.AtomicInteger evidenceCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    private String nextEvidenceId() {
        return String.format("EV-%04d", evidenceCounter.incrementAndGet());
    }

    /** Ergebnis einer Erfassung; bewusst kompakt und ohne Logtexte. */
    public record CollectionOutcome(
            String snapshotId,
            CollectionStatus collectionStatus,
            String dataMode,
            String applicationId,
            String environment,
            int buildNumber,
            List<String> jobPath,
            String jenkinsInstance,
            String namespace,
            String workloadKind,
            String workloadName,
            InvestigationWindow window,
            List<SourceSummary> sources,
            List<SnapshotLink> links,
            List<EvidenceView> evidence,
            List<String> warnings,
            List<OpsError> errors) {

        public CollectionOutcome {
            jobPath = jobPath == null ? List.of() : List.copyOf(jobPath);
            sources = sources == null ? List.of() : List.copyOf(sources);
            links = links == null ? List.of() : List.copyOf(links);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("snapshotId", snapshotId);
            map.put("collectionStatus", collectionStatus.wireName());
            map.put("dataMode", dataMode);
            map.put("applicationId", applicationId);
            map.put("environment", environment);
            Map<String, Object> build = new LinkedHashMap<>();
            build.put("instanceId", jenkinsInstance);
            build.put("jobPath", jobPath);
            build.put("buildNumber", buildNumber);
            map.put("buildRef", build);
            // Dieselbe Struktur wie SnapshotSummary: buildRef und target als Objekte, damit
            // collect, get_snapshot und inspect dieselbe Form liefern.
            map.put("jenkinsInstance", jenkinsInstance);
            map.put("jobPath", jobPath);
            map.put("buildNumber", buildNumber);
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("namespace", namespace);
            target.put("workloadKind", workloadKind);
            target.put("workloadName", workloadName);
            map.put("target", target);
            map.put("namespace", namespace);
            map.put("workloadKind", workloadKind);
            map.put("workloadName", workloadName);
            map.put("investigationWindow", window == null ? null : window.toMap());
            map.put("sourceResults", sources.stream().map(SourceSummary::toMap).toList());
            map.put("links", links.stream().map(SnapshotLink::toMap).toList());
            map.put("evidenceIndex", evidence.stream().map(EvidenceView::toMap).toList());
            if (!warnings.isEmpty()) {
                map.put("warnings", warnings);
            }
            if (!errors.isEmpty()) {
                map.put("errors", errors.stream().map(SourceSummary::errorToMap).toList());
            }
            return map;
        }
    }

    /**
     * Kompakter Evidence-Eintrag fuer Tool- und CLI-Ausgaben.
     *
     * @param id          Evidence-ID
     * @param type        Evidence-Art
     * @param sourceId    Quellenkennung
     * @param observedAt  fachlicher Zeitbezug
     * @param lineCount   Zeilenanzahl
     * @param description Kurzbeschreibung des Inhalts
     * @param path        relativer Pfad im Snapshot
     */
    public record EvidenceView(String id, String type, String sourceId, Instant observedAt, int lineCount,
            String description, String path) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidenceId", id);
            map.put("type", type);
            map.put("sourceId", sourceId);
            map.put("observedAt", observedAt == null ? null : observedAt.toString());
            map.put("lineCount", lineCount);
            map.put("description", description);
            map.put("locator", Map.of("path", path, "lineCount", lineCount));
            return map;
        }
    }

    /** Erfasst einen Snapshot. Ein partieller Snapshot ist ein regulaeres Ergebnis. */
    public CollectionOutcome collect(ResolvedTarget target) {
        Instant startedAt = clock.now();
        List<String> warnings = new ArrayList<>(target.warnings());
        List<OpsError> errors = new ArrayList<>();
        List<RawEvidence> evidence = new ArrayList<>();
        List<SourceSummary> sources = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>(versionInfo.toMap());

        BuildSnapshot build = null;
        ConsoleLogEvidence consoleLog = null;
        ClusterCollection cluster = null;
        ResolvedConfiguration configuration = null;
        List<ProbeComparison.Finding> findings = new ArrayList<>();
        String scenarioId = target.scenarioId();

        // 1) Jenkins
        JenkinsDefinition jenkins = config.jenkins(target.definition().deployment().jenkins());
        BuildReader buildReader = buildReaders.apply(jenkins == null ? null : jenkins.id());
        if (buildReader == null) {
            sources.add(new SourceSummary(jenkinsRefName(jenkins), SourceStatus.NOT_ATTEMPTED, startedAt, List.of(),
                    List.of("Kein BuildReader fuer diese Jenkins-Instanz verfuegbar."), List.of()));
        } else {
            SourceResult<BuildSnapshot> metadata = buildReader.collectMetadata(target, target.budget());
            sources.add(summarize(metadata, "jenkins-metadaten"));
            errors.addAll(metadata.errors());
            if (metadata.status().isUsable() && metadata.data() != null) {
                build = metadata.data();
                evidence.add(jenkinsMetadataEvidence(target, build, metadata));
                normalized.put("build", build.toSummaryMap());
            }
            SourceResult<ConsoleLogEvidence> log = buildReader.collectConsoleLog(target, target.budget());
            sources.add(summarize(log, "jenkins-console-log"));
            errors.addAll(log.errors());
            if (log.status().isUsable() && log.data() != null) {
                consoleLog = log.data();
                evidence.add(consoleLogEvidence(target, consoleLog));
                normalized.put("jenkinsConsoleLog", consoleLog.toSummaryMap());
            }
        }

        // 2) OpenShift: zeitnahe Erfassung
        if (clusterReader != null) {
            SourceResult<ClusterCollection> clusterResult = clusterReader.collect(target, target.budget());
            sources.add(summarize(clusterResult, "openshift"));
            warnings.addAll(cap(clusterResult.notes()));
            errors.addAll(clusterResult.errors());
            if (clusterResult.status().isUsable() && clusterResult.data() != null) {
                cluster = clusterResult.data();
                evidence.addAll(clusterEvidence(cluster));
                normalized.put("cluster", cluster.toSummaryMap());
            }
        } else {
            sources.add(new SourceSummary("openshift", SourceStatus.NOT_ATTEMPTED, startedAt, List.of(),
                    List.of("Kein ClusterReader konfiguriert."), List.of()));
        }

        // 3) Revision
        RevisionProvenance revision = resolveRevision(target, build, consoleLog, consoleLogEvidenceId(evidence),
                warnings);
        if (revision.commit() != null) {
            versions.put("configRevision", revision.shortCommit() == null ? revision.commit() : revision.shortCommit());
        }

        // 4) Konfiguration
        if (configurationReader != null) {
            SourceResult<ResolvedConfiguration> configResult = configurationReader.collect(target, revision,
                    target.budget());
            sources.add(summarize(configResult, "konfiguration"));
            warnings.addAll(cap(configResult.notes()));
            errors.addAll(configResult.errors());
            if (configResult.status().isUsable() && configResult.data() != null) {
                configuration = configResult.data();
                evidence.addAll(configurationEvidence(configuration, revision));
                normalized.put("configuration", configurationSummary(configuration));
            }
        } else {
            sources.add(new SourceSummary("konfiguration", SourceStatus.NOT_ATTEMPTED, startedAt, List.of(),
                    List.of("Kein ConfigurationReader konfiguriert."), List.of()));
        }

        // 5) Mechanische Befunde
        Map<String, List<ProbeConfig>> configProbes = configProbesByContainer(configuration);
        if (cluster != null && cluster.workload() != null) {
            OwnershipGraph graph = new OwnershipGraph(cluster);
            List<RevisionSnapshot> revisions = graph.revisionsOf(cluster.workload());
            List<PodSnapshot> pods = graph.podsOf(revisions);
            Map<String, String> podEvidence = new LinkedHashMap<>();
            for (RawEvidence item : evidence) {
                if (item.type() == EvidenceType.KUBERNETES_POD && item.object() != null) {
                    podEvidence.put(item.object().uid(), item.id());
                }
            }
            for (PodSnapshot pod : pods) {
                List<String> refs = podEvidence.containsKey(pod.uid()) ? List.of(podEvidence.get(pod.uid())) : List.of();
                findings.addAll(ProbeComparison.containerFindings(pod, refs, configProbes));
            }
            Map<String, String> eventEvidence = new LinkedHashMap<>();
            for (RawEvidence item : evidence) {
                if (item.type() == EvidenceType.KUBERNETES_EVENT && item.structured() instanceof EventSnapshot event) {
                    eventEvidence.put(event.dedupeKey(), item.id());
                }
            }
            findings.addAll(ProbeComparison.eventFindings(cluster.events(), eventEvidence));
            findings.addAll(ProbeComparison.previousLogFindings(cluster.logs()));
            List<ProbeConfig> podProbes = pods.stream()
                    .flatMap(pod -> pod.probesFromPod().stream())
                    .toList();
            List<String> configRefs = evidence.stream()
                    .filter(item -> item.type() == EvidenceType.GIT_CONFIGURATION)
                    .map(RawEvidence::id)
                    .toList();
            findings.addAll(ProbeComparison.compareProbes(podProbes, configProbes, configRefs));
            OwnershipGraph.RevisionAmbiguity ambiguity = graph.ambiguity(cluster.workload());
            if (ambiguity.ambiguous()) {
                warnings.add(ambiguity.reason());
            }
            List<String> duplicates = graph.duplicatePodNames();
            if (!duplicates.isEmpty()) {
                warnings.add("Gleichnamige Pods mit unterschiedlicher UID erfasst: " + String.join(", ", duplicates)
                        + ". Events werden ueber die UID zugeordnet.");
            }
        }

        // 6) Links
        List<SnapshotLink> links = buildLinks(target, build, cluster, configuration, revision, sources, consoleLog,
                evidence);
        for (SnapshotLink link : links) {
            if (link.strength() == LinkStrength.AMBIGUOUS || link.strength() == LinkStrength.UNLINKED) {
                warnings.add("Zuordnung " + link.relation() + " ist " + link.strength().name().toLowerCase(
                        java.util.Locale.ROOT) + ": " + link.reason());
            }
        }

        // 7) Zeitfenster
        InvestigationWindow window = window(target, build, startedAt);

        // 8) Status
        CollectionStatus status = aggregateStatus(sources, cluster, configuration, build);
        if (target.budget().anyExhaustion()) {
            status = status == CollectionStatus.FAILED ? status : CollectionStatus.PARTIAL;
            warnings.add("Budgetgrenzen erreicht: " + String.join(", ", target.budget().exhaustions()));
        }
        for (SourceSummary source : sources) {
            for (OpsError error : source.errors()) {
                if (!errors.contains(error)) {
                    errors.add(error);
                }
            }
        }

        normalized.put("findings", findings.stream().map(ProbeComparison.Finding::toMap).toList());
        normalized.put("revision", revision.toMap());
        normalized.put("dataMode", target.dataMode().wireName());
        normalized.put("synthetic", target.isFixture());
        if (target.isFixture() && scenarioId != null) {
            normalized.put("scenarioId", scenarioId);
        }
        normalized.put("budget", Map.of(
                "requestsUsed", target.budget().requestsUsed(),
                "inputBytesUsed", target.budget().inputBytesUsed(),
                "maxRequests", target.budget().maxRequests(),
                "collectTimeoutSeconds", config.limits().collectTimeoutSeconds()));
        normalized.put("toolVersions", versions);

        String workloadKind = cluster != null && cluster.workload() != null
                ? cluster.workload().kind()
                : (target.definition().workload() == null ? null
                        : target.definition().workload().kind().kubernetesKind());
        String workloadName = cluster != null && cluster.workload() != null
                ? cluster.workload().name()
                : (target.definition().workload() == null ? null : target.definition().workload().name());

        SnapshotDraft draft = new SnapshotDraft(
                target.applicationId(),
                target.environment(),
                target.dataMode(),
                target.buildRef(),
                build != null && build.metadataAvailable(),
                target.clusterId(),
                cluster == null ? null : cluster.server(),
                cluster == null ? null : cluster.context(),
                target.namespace(),
                workloadKind,
                workloadName,
                startedAt,
                clock.now(),
                window,
                status,
                sources,
                links,
                evidence,
                normalized,
                versions,
                warnings,
                errors);

        ch.so.agi.ops.snapshot.SnapshotSaveResult saved = store.save(draft);
        List<SourceSummary> sourcesWithIds = withEvidenceIds(sources, evidence, target);
        return new CollectionOutcome(saved.snapshotId().value(), status, target.dataMode().wireName(), target.applicationId(),
                target.environment(), target.buildRef().buildNumber(), target.buildRef().jobPath(),
                target.buildRef().instanceId(), target.namespace(), workloadKind, workloadName, window,
                sourcesWithIds, links, evidenceViews(evidence), warnings, errors);
    }

    private String jenkinsRefName(JenkinsDefinition jenkins) {
        return jenkins == null ? "jenkins" : jenkins.id();
    }

    private String consoleLogEvidenceId(List<RawEvidence> evidence) {
        return evidence.stream()
                .filter(item -> item.type() == EvidenceType.JENKINS_CONSOLE_LOG)
                .map(RawEvidence::id)
                .findFirst()
                .orElse(null);
    }

    /** Kompakte Evidence-Sicht fuer Tool- und CLI-Ausgaben; es werden keine Logtexte mitgegeben. */
    private List<EvidenceView> evidenceViews(List<RawEvidence> evidence) {
        List<EvidenceView> views = new ArrayList<>();
        for (RawEvidence item : evidence) {
            views.add(new EvidenceView(item.id(), item.type().wireName(), item.sourceId(), item.observedAt(),
                    item.contentLines().size(), item.description(), "evidence/" + item.id() + ".txt"));
        }
        return List.copyOf(views);
    }

    /**
     * Ergebnis einer Quelle fuer das Manifest. Die Evidence-IDs werden nach der Vergabe
     * gesetzt; hier bleibt die Quelle ohne IDs, weil zum Zeitpunkt des Aufrufs noch nicht
     * feststeht, welche Evidence sie erzeugt.
     */
    private SourceSummary summarize(SourceResult<?> result, String label) {
        return new SourceSummary(result.sourceId() + ":" + label, result.status(), result.collectedAt(),
                List.of(), result.notes(), result.errors());
    }

    /**
     * SourceSummaries mit den tatsaechlich vergebenen Evidence-IDs. Die Zuordnung erfolgt
     * ueber die Quellenkennung der Evidence, damit das Manifest dieselben IDs nennt wie die
     * Werkzeugausgabe.
     */
    private List<SourceSummary> withEvidenceIds(List<SourceSummary> sources, List<RawEvidence> evidence,
            ResolvedTarget target) {
        List<SourceSummary> result = new ArrayList<>(sources.size());
        for (SourceSummary source : sources) {
            List<String> ids = new ArrayList<>();
            for (RawEvidence item : evidence) {
                if (belongsToSource(source, item, target)) {
                    ids.add(item.id());
                }
            }
            result.add(new SourceSummary(source.sourceId(), source.status(), source.collectedAt(), ids,
                    source.notes(), source.errors()));
        }
        return List.copyOf(result);
    }

    private boolean belongsToSource(SourceSummary source, RawEvidence evidence, ResolvedTarget target) {
        String sourceId = source.sourceId();
        String jenkins = target.definition().deployment().jenkins();
        return switch (evidence.type()) {
            case JENKINS_BUILD_METADATA -> sourceId.equals(jenkins + ":jenkins-metadaten");
            case JENKINS_CONSOLE_LOG -> sourceId.equals(jenkins + ":jenkins-console-log");
            case KUBERNETES_WORKLOAD, KUBERNETES_REVISION, KUBERNETES_POD, KUBERNETES_EVENT,
                    KUBERNETES_CONTAINER_LOG -> sourceId.endsWith(":openshift");
            case GIT_CONFIGURATION -> sourceId.endsWith(":konfiguration");
            case FIXTURE_SCENARIO -> sourceId.endsWith(":fixture");
        };
    }

    /** Begrenzt Hinweislisten, damit eine lange Notizliste die Ausgabe nicht ueberflutet. */
    private static List<String> cap(List<String> notes) {
        return notes.size() <= 3 ? List.copyOf(notes) : List.copyOf(notes.subList(0, 3));
    }

    /** Rendert eine strukturierte Projektion in stabile, zeilennummerierte Zeilen. */
    private List<String> renderLines(Object value) {
        String rendered = renderer.render(value);
        return List.of(rendered.split("\n", -1));
    }

    private RawEvidence jenkinsMetadataEvidence(ResolvedTarget target, BuildSnapshot build,
            SourceResult<BuildSnapshot> result) {
        Map<String, Object> summary = build.toSummaryMap();
        return new RawEvidence(nextEvidenceId(), EvidenceType.JENKINS_BUILD_METADATA,
                target.definition().deployment().jenkins(), null, build.startedAt(),
                Locator.of("/job/" + target.jobPathString() + "/" + build.ref().buildNumber() + "/api/json"),
                renderLines(summary), summary, "Jenkins-Buildmetadaten", result.notes());
    }

    private RawEvidence consoleLogEvidence(ResolvedTarget target, ConsoleLogEvidence log) {
        return new RawEvidence(nextEvidenceId(), EvidenceType.JENKINS_CONSOLE_LOG,
                target.definition().deployment().jenkins(), null, null,
                new Locator("/job/" + target.jobPathString() + "/" + target.buildRef().buildNumber() + "/consoleText",
                        log.firstLineNumber(), log.firstLineNumber() + Math.max(0, log.lines().size() - 1),
                        log.headRequestedFromByte(), log.truncated(), log.truncatedReason()),
                log.lines(), log.toSummaryMap(), "Jenkins-Console-Log (bereinigt)", List.of());
    }

    private List<RawEvidence> clusterEvidence(ClusterCollection cluster) {
        List<RawEvidence> evidence = new ArrayList<>();
        WorkloadSnapshot workload = cluster.workload();
        if (workload != null) {
            Map<String, Object> summary = workloadSummary(workload);
            evidence.add(new RawEvidence(nextEvidenceId(), EvidenceType.KUBERNETES_WORKLOAD, cluster.clusterId(),
                    new ch.so.agi.ops.domain.ObjectRef(workload.kind(), workload.namespace(), workload.name(),
                            workload.uid()),
                    workload.creationTimestamp(),
                    Locator.of("/apis/apps/v1/namespaces/" + workload.namespace() + "/deployments/" + workload.name()),
                    renderLines(summary), summary, "Workload, Generations- und Condition-Daten", List.of()));
        }
        for (RevisionSnapshot revision : cluster.revisions()) {
            Map<String, Object> summary = revision.toSummaryMap();
            evidence.add(new RawEvidence(nextEvidenceId(), EvidenceType.KUBERNETES_REVISION,
                    cluster.clusterId(),
                    new ch.so.agi.ops.domain.ObjectRef(revision.kind(), revision.namespace(), revision.name(),
                            revision.uid()),
                    revision.creationTimestamp(),
                    Locator.of("/apis/apps/v1/namespaces/" + revision.namespace() + "/replicasets/"
                            + revision.name()),
                    renderLines(summary), summary, "Revision (ReplicaSet/ReplicationController)", List.of()));
        }
        for (PodSnapshot pod : cluster.pods()) {
            Map<String, Object> summary = podDetail(pod);
            evidence.add(new RawEvidence(nextEvidenceId(), EvidenceType.KUBERNETES_POD, cluster.clusterId(),
                    new ch.so.agi.ops.domain.ObjectRef("Pod", pod.namespace(), pod.name(), pod.uid()),
                    pod.creationTimestamp(),
                    Locator.of("/api/v1/namespaces/" + pod.namespace() + "/pods/" + pod.name()),
                    renderLines(summary), summary, "Pod, Containerzustaende und Pod-Probes", List.of()));
        }
        for (EventSnapshot event : cluster.events()) {
            Map<String, Object> summary = event.toSummaryMapWithMessage();
            List<String> notes = new ArrayList<>();
            if (event.isAggregated()) {
                notes.add("Aggregierter Zaehler: " + event.count() + " Beobachtungen. Daraus wird keine "
                        + "sekundengenaue Ereignisliste abgeleitet.");
            }
            if (event.isDuplicate()) {
                notes.add("Dasselbe Event lag bereits im Format " + event.duplicateOfFormat()
                        + " vor und wird nicht als unabhaengiger zweiter Beleg gezaehlt.");
            }
            evidence.add(new RawEvidence(nextEvidenceId(), EvidenceType.KUBERNETES_EVENT, cluster.clusterId(),
                    event.involvedObject(), event.bestKnownTime(),
                    Locator.of(event.sourcePath() == null ? "/api/v1/events" : event.sourcePath()),
                    renderLines(summary), event, "Kubernetes-Event (UID-zugeordnet)", notes));
        }
        for (ContainerLog log : cluster.logs()) {
            List<String> notes = new ArrayList<>();
            if (log.truncated()) {
                notes.add(log.truncationNote().isBlank() ? "Log wurde nach Bytebudget abgeschnitten."
                        : log.truncationNote());
            }
            if (!log.available() && !log.skipReason().isBlank()) {
                notes.add("Nicht gelesen: " + log.skipReason());
            }
            if (log.errorCode() != null) {
                notes.add("Fehler beim Lesen: " + log.errorCode() + " - " + log.errorMessage());
            }
            evidence.add(new RawEvidence(nextEvidenceId(), EvidenceType.KUBERNETES_CONTAINER_LOG,
                    cluster.clusterId(),
                    new ch.so.agi.ops.domain.ObjectRef("Pod", cluster.namespace(), log.podName(), log.podUid()),
                    null,
                    Locator.of("/api/v1/namespaces/" + cluster.namespace() + "/pods/" + log.podName()
                            + "/log?container=" + log.containerName() + (log.previous() ? "&previous=true" : "")),
                    log.lines(), log.toSummaryMap(), "Container-Log (bereinigt)", notes));
        }
        return List.copyOf(evidence);
    }

    private Map<String, Object> workloadSummary(WorkloadSnapshot workload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", workload.kind());
        map.put("apiVersion", workload.apiVersion());
        map.put("namespace", workload.namespace());
        map.put("name", workload.name());
        map.put("uid", workload.uid());
        map.put("generation", workload.generation());
        map.put("observedGeneration", workload.observedGeneration());
        map.put("desiredReplicas", workload.desiredReplicas());
        map.put("updatedReplicas", workload.updatedReplicas());
        map.put("readyReplicas", workload.readyReplicas());
        map.put("availableReplicas", workload.availableReplicas());
        map.put("rollingOut", workload.rollingOut());
        map.put("creationTimestamp", workload.creationTimestamp() == null
                ? null
                : workload.creationTimestamp().toString());
        map.put("revisionHint", workload.revisionHint());
        map.put("podTemplateUid", workload.podTemplateUid());
        map.put("conditions", workload.conditions().stream().map(condition -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", condition.type());
            item.put("status", condition.status());
            item.put("reason", condition.reason());
            item.put("message", condition.message());
            item.put("lastTransitionTime", condition.lastTransitionTime() == null
                    ? null
                    : condition.lastTransitionTime().toString());
            item.put("lastUpdateTime", condition.lastUpdateTime() == null
                    ? null
                    : condition.lastUpdateTime().toString());
            return item;
        }).toList());
        map.put("templateProbes", probeMap(workload.templateProbes()));
        return map;
    }

    private Map<String, Object> podDetail(PodSnapshot pod) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("namespace", pod.namespace());
        map.put("name", pod.name());
        map.put("uid", pod.uid());
        map.put("phase", pod.phase());
        map.put("nodeName", pod.nodeName());
        map.put("creationTimestamp", pod.creationTimestamp() == null ? null : pod.creationTimestamp().toString());
        map.put("restartPolicy", pod.restartPolicy());
        map.put("ready", pod.isReady());
        map.put("containersReady", pod.isContainersReady());
        map.put("conditions", pod.conditions().stream().map(condition -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", condition.type());
            item.put("status", condition.status());
            item.put("reason", condition.reason());
            item.put("message", condition.message());
            item.put("lastTransitionTime", condition.lastTransitionTime() == null
                    ? null
                    : condition.lastTransitionTime().toString());
            return item;
        }).toList());
        map.put("ownerReferences", pod.ownerReferences().stream().map(owner -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", owner.kind());
            item.put("name", owner.name());
            item.put("uid", owner.uid());
            item.put("controller", owner.controller());
            return item;
        }).toList());
        map.put("containers", pod.containers().stream().map(this::containerDetail).toList());
        map.put("initContainers", pod.initContainers().stream().map(this::containerDetail).toList());
        map.put("probesFromPod", pod.probesFromPod().stream().map(this::probeDetail).toList());
        map.put("revisionHint", pod.revisionHint());
        return map;
    }

    private Map<String, Object> containerDetail(ContainerSnapshot container) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", container.name());
        map.put("containerClass", container.containerClass().name());
        map.put("image", container.image());
        map.put("imageId", container.imageId());
        map.put("ready", container.ready());
        map.put("restartCount", container.restartCount());
        map.put("started", container.started());
        map.put("state", stateDetail(container.state()));
        map.put("lastState", stateDetail(container.lastState()));
        ContainerSnapshot.ContainerObservations observations = container.observations();
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("hadRestart", observations.hadRestart());
        obs.put("lastStateTerminated", observations.lastStateTerminated());
        obs.put("lastTerminationReason", observations.lastTerminationReason());
        obs.put("lastExitCode", observations.lastExitCode());
        obs.put("flags", observations.flags());
        map.put("observations", obs);
        return map;
    }

    private Map<String, Object> stateDetail(ContainerSnapshot.ContainerState state) {
        if (state == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", state.kind().name());
        map.put("reason", state.reason());
        map.put("message", state.message());
        map.put("exitCode", state.exitCode());
        map.put("signal", state.signal());
        map.put("startedAt", state.startedAt() == null ? null : state.startedAt().toString());
        map.put("finishedAt", state.finishedAt() == null ? null : state.finishedAt().toString());
        return map;
    }

    private Map<String, Object> probeMap(Map<String, ProbeConfig> probes) {
        Map<String, Object> map = new LinkedHashMap<>();
        probes.forEach((container, probe) -> map.put(container, probeDetail(probe)));
        return map;
    }

    private Map<String, Object> probeDetail(ProbeConfig probe) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("container", probe.containerName());
        map.put("kind", probe.kind() == null ? null : probe.kind().name());
        map.put("handler", probe.handlerKind() == null ? null : probe.handlerKind().name());
        map.put("httpPath", probe.httpPath());
        map.put("httpPort", probe.httpPort());
        map.put("httpScheme", probe.httpScheme());
        map.put("httpHeaderNames", probe.httpHeaderNames());
        map.put("initialDelaySeconds", probe.initialDelaySeconds());
        map.put("periodSeconds", probe.periodSeconds());
        map.put("timeoutSeconds", probe.timeoutSeconds());
        map.put("successThreshold", probe.successThreshold());
        map.put("failureThreshold", probe.failureThreshold());
        map.put("simplifiedStartBudgetSeconds", probe.simplifiedStartBudgetSeconds());
        map.put("redacted", probe.redacted());
        if (!probe.redactionNote().isBlank()) {
            map.put("redactionNote", probe.redactionNote());
        }
        return map;
    }

    private List<RawEvidence> configurationEvidence(ResolvedConfiguration configuration,
            RevisionProvenance revision) {
        Map<String, Object> summary = configurationSummary(configuration);
        return List.of(new RawEvidence(nextEvidenceId(), EvidenceType.GIT_CONFIGURATION,
                configuration.repositoryId(), null, null,
                new Locator("git:" + configuration.repositoryId() + "@"
                        + (revision.commit() == null ? "unbekannt" : revision.shortCommit()),
                        null, null, null, false, revision.note()),
                renderLines(summary), summary, "Aufgeloeste Konfiguration mit Herkunft", List.of()));
    }

    private Map<String, Object> configurationSummary(ResolvedConfiguration configuration) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("repositoryId", configuration.repositoryId());
        map.put("revision", configuration.revision().toMap());
        map.put("status", configuration.status().name());
        map.put("containerNames", configuration.containerNames());
        map.put("currentReferenceFiles", configuration.currentReferenceFiles());
        Map<String, Object> containers = new LinkedHashMap<>();
        configuration.workloads().forEach((name, containerConfig) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("image", containerConfig.image());
            item.put("ports", containerConfig.ports());
            item.put("resources", containerConfig.resources());
            item.put("pointerBase", containerConfig.rawPointerBase());
            Map<String, Object> probes = new LinkedHashMap<>();
            containerConfig.probes().forEach((kind, probe) -> probes.put(kind, probeDetail(probe)));
            item.put("probes", probes);
            containers.put(name, item);
        });
        map.put("containers", containers);
        map.put("files", configuration.files().stream().map(FileReadResult::toMap).toList());
        Map<String, Object> rollout = new LinkedHashMap<>();
        configuration.rolloutTimeParameters().forEach((key, value) -> rollout.put(key, value.toMap()));
        map.put("rolloutTimeParameters", rollout);
        return map;
    }

    private Map<String, List<ProbeConfig>> configProbesByContainer(ResolvedConfiguration configuration) {
        Map<String, List<ProbeConfig>> map = new LinkedHashMap<>();
        if (configuration == null) {
            return map;
        }
        configuration.workloads().forEach((name, containerConfig) ->
                map.put(name, List.copyOf(containerConfig.probes().values())));
        return map;
    }

    private List<EvidenceView> evidenceViews(List<RawEvidence> evidence, List<String> configRefs) {
        List<EvidenceView> views = new ArrayList<>();
        for (RawEvidence item : evidence) {
            String description = switch (item.type()) {
                case JENKINS_BUILD_METADATA -> "Jenkins-Buildmetadaten";
                case JENKINS_CONSOLE_LOG -> "Jenkins-Console-Log (bereinigt)";
                case KUBERNETES_WORKLOAD -> "Workload, Generations- und Condition-Daten";
                case KUBERNETES_REVISION -> "Revision (ReplicaSet/ReplicationController)";
                case KUBERNETES_POD -> "Pod, Containerzustaende und Pod-Probes";
                case KUBERNETES_CONTAINER_LOG -> "Container-Log (bereinigt)";
                case KUBERNETES_EVENT -> "Kubernetes-Event (UID-zugeordnet)";
                case GIT_CONFIGURATION -> "Aufgeloeste Konfiguration mit Herkunft";
                case FIXTURE_SCENARIO -> "Synthetisches Szenario";
            };
            views.add(new EvidenceView(item.id(), item.type().wireName(), item.sourceId(), item.observedAt(),
                    item.contentLines().size(), description, "evidence/" + item.id() + ".txt"));
        }
        return List.copyOf(views);
    }

    private RevisionProvenance resolveRevision(ResolvedTarget target, BuildSnapshot build,
            ConsoleLogEvidence consoleLog, String consoleEvidenceId, List<String> warnings) {
        ConfigurationDefinition definition = target.definition().configuration();
        ConfigurationDefinition.RevisionDefinition revisionDefinition = definition.revision();
        String repositoryId = revisionDefinition.repositoryId() == null
                ? definition.repository()
                : revisionDefinition.repositoryId();
        if (revisionDefinition.source() == ConfigurationDefinition.RevisionDefinition.Source.OPERATOR_SUPPLIED) {
            if (revisionDefinition.forBuild() == null
                    || revisionDefinition.forBuild() != target.buildRef().buildNumber()) {
                warnings.add("Die Betreiberangabe der Revision gilt fuer Build "
                        + (revisionDefinition.forBuild() == null ? "unbekannt" : revisionDefinition.forBuild())
                        + ", untersucht wird Build " + target.buildRef().buildNumber()
                        + ". Die Angabe wird nicht uebernommen (REVISION_UNKNOWN).");
                return RevisionProvenance.unknown(repositoryId,
                        "Betreiberangabe passt nicht zur untersuchten Buildnummer.");
            }
            if (revisionDefinition.commit() == null || !revisionDefinition.commit().matches("[0-9a-fA-F]{7,64}")) {
                warnings.add("Die Betreiberangabe der Revision ist kein gueltiger Commit-Hash (REVISION_UNKNOWN).");
                return RevisionProvenance.unknown(repositoryId, "Betreiberangabe ist kein Commit-Hash.");
            }
            return RevisionProvenance.operatorSupplied(repositoryId, revisionDefinition.commit(),
                    target.buildRef().buildNumber());
        }

        // jenkins-scm: aus strukturierten Daten den vollstaendigen Commit des konfigurierten
        // Repositories zuordnen. Kein Treffer oder mehrere widerspruechliche Treffer sind eine
        // explizite Luecke; es wird kein beliebiger SHA uebernommen.
        if (build == null) {
            warnings.add("Keine Jenkins-Buildmetadaten verfuegbar; die Konfigurationsrevision bleibt "
                    + "unbekannt (REVISION_UNKNOWN).");
            return RevisionProvenance.unknown(repositoryId,
                    "Keine strukturierten Jenkins-Daten zur Revisionszuordnung.");
        }
        // Mehrere Aktionen desselben Repositories (z. B. BuildData und GitSCM) sind kein
        // Widerspruch: Sie werden auf einen Eintrag je Repository und Commit reduziert. Erst
        // mehrere UNTERSCHIEDLICHE Commits sind eine mehrdeutige Zuordnung.
        java.util.LinkedHashMap<String, ch.so.agi.ops.domain.jenkins.RepositoryDescriptor> distinct =
                new java.util.LinkedHashMap<>();
        List<ch.so.agi.ops.domain.jenkins.RepositoryDescriptor> withCommit = new ArrayList<>();
        for (var descriptor : build.scmRepositories()) {
            if (!repositoryId.equalsIgnoreCase(descriptor.repositoryIdHint())) {
                continue;
            }
            if (descriptor.commit() != null && descriptor.commit().matches("[0-9a-fA-F]{7,64}")) {
                distinct.putIfAbsent(descriptor.commit().toLowerCase(java.util.Locale.ROOT), descriptor);
                withCommit.add(descriptor);
            }
        }
        List<ch.so.agi.ops.domain.jenkins.RepositoryDescriptor> matches = List.copyOf(distinct.values());
        if (matches.size() == 1) {
            var descriptor = matches.get(0);
            String shortCommit = descriptor.commit().length() >= 8
                    ? descriptor.commit().substring(0, 8)
                    : descriptor.commit();
            return new RevisionProvenance(repositoryId, descriptor.commit(), shortCommit,
                    RevisionProvenance.Source.JENKINS_SCM, target.buildRef().buildNumber(),
                    "jenkins-build-metadata",
                    "Vollstaendiger Commit aus strukturierten Jenkins-SCM-Daten zugeordnet.");
        }
        if (matches.size() > 1) {
            warnings.add("Mehrere widerspruechliche Commits passen zum konfigurierten Repository; die Zuordnung "
                    + "bleibt mehrdeutig (REVISION_UNVERIFIED, kein willkuerlicher SHA).");
            return RevisionProvenance.unknown(repositoryId,
                    "Mehrere widerspruechliche Commits fuer das konfigurierte Repository.");
        }
        // Fallback: nachweislich erkannter Repo-Checkout im Console-Log, mit Zeilenbeleg.
        if (consoleLog != null && consoleEvidenceId != null) {
            String commit = findCommitInConsoleLog(consoleLog, repositoryId);
            if (commit != null) {
                String shortCommit = commit.length() >= 8 ? commit.substring(0, 8) : commit;
                warnings.add("Die Revision stammt aus einem im Console-Log erkannten Checkout und ist damit "
                        + "nicht unabhaengig bestaetigt (REVISION_UNVERIFIED).");
                return new RevisionProvenance(repositoryId, commit, shortCommit,
                        RevisionProvenance.Source.JENKINS_CONSOLE_CHECKOUT, target.buildRef().buildNumber(),
                        consoleEvidenceId,
                        "Commit aus Console-Log-Checkoutzeile; Zuordnung nicht unabhaengig bestaetigt.");
            }
        }
        warnings.add("Kein Commit des konfigurierten Repositories " + repositoryId
                + " in den Jenkins-Daten gefunden (REVISION_UNKNOWN). Es wird nicht ersatzweise HEAD oder main "
                + "verwendet.");
        return RevisionProvenance.unknown(repositoryId,
                "Kein passender SCM-Eintrag in den strukturierten Jenkins-Daten.");
    }

    /**
     * Sucht einen Checkout-Hinweis im Console-Log. Nur Zeilen, die den Ordnernamen des
     * konfigurierten Repositories nennen, werden beruecksichtigt. Ein beliebiger SHA aus dem
     * Log ist nicht automatisch die Revision von {@code openshift-templates}.
     */
    static String findCommitInConsoleLog(ConsoleLogEvidence consoleLog, String repositoryId) {
        String shortName = repositoryId.contains("/")
                ? repositoryId.substring(repositoryId.lastIndexOf('/') + 1)
                : repositoryId;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([0-9a-f]{40})\\b");
        for (String line : consoleLog.lines()) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains(shortName.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (!(lower.contains("commit") || lower.contains("checkout") || lower.contains("revision"))) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private InvestigationWindow window(ResolvedTarget target, BuildSnapshot build, Instant collectedAt) {
        if (build == null || build.startedAt() == null) {
            return InvestigationWindow.unknown(collectedAt);
        }
        Instant finish = build.finishedAt();
        if (finish != null) {
            return new InvestigationWindow(build.startedAt().minus(InvestigationWindow.PADDING),
                    finish.plus(InvestigationWindow.PADDING),
                    InvestigationWindow.TimeBasis.BUILD_START_AND_END,
                    "Abgeschlossener Jenkins-Build: Start minus 2 Minuten bis Ende plus 2 Minuten.");
        }
        return new InvestigationWindow(build.startedAt().minus(InvestigationWindow.PADDING), collectedAt,
                InvestigationWindow.TimeBasis.BUILD_START_AND_COLLECTION,
                "Laufender oder unvollstaendiger Jenkins-Build: Start minus 2 Minuten bis zur Erfassungszeit; "
                        + "die Erfassungszeit ist eine vorlaeufige Obergrenze.");
    }

    private CollectionStatus aggregateStatus(List<SourceSummary> sources, ClusterCollection cluster,
            ResolvedConfiguration configuration, BuildSnapshot build) {
        boolean anyData = cluster != null || configuration != null || build != null;
        if (!anyData) {
            return CollectionStatus.FAILED;
        }
        return SourceSummary.aggregate(CollectionStatus.COMPLETE, sources);
    }

    /** Evidence-IDs zu den angegebenen Arten, in Erfassungsreihenfolge. */
    private static List<String> evidenceRefs(List<RawEvidence> evidence, EvidenceType... types) {
        List<String> ids = new ArrayList<>();
        for (RawEvidence item : evidence) {
            for (EvidenceType type : types) {
                if (item.type() == type) {
                    ids.add(item.id());
                }
            }
        }
        return List.copyOf(ids);
    }

    private List<SnapshotLink> buildLinks(ResolvedTarget target, BuildSnapshot build, ClusterCollection cluster,
            ResolvedConfiguration configuration, RevisionProvenance revision, List<SourceSummary> sources,
            ConsoleLogEvidence consoleLog, List<RawEvidence> evidence) {
        List<SnapshotLink> links = new ArrayList<>();
        String buildNode = target.buildNodeId();
        List<String> buildEvidence = evidenceRefs(evidence, EvidenceType.JENKINS_BUILD_METADATA,
                EvidenceType.JENKINS_CONSOLE_LOG);

        String workloadNode = target.workloadNodeId();
        LinkStrength buildToWorkload = LinkStrength.UNLINKED;
        String reason = "Kein Workload erfasst.";
        List<String> refs = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        if (cluster != null && cluster.workload() != null) {
            refs.addAll(evidenceRefs(evidence, EvidenceType.KUBERNETES_WORKLOAD));
            OwnershipGraph graph = new OwnershipGraph(cluster);
            OwnershipGraph.RevisionAmbiguity ambiguity = graph.ambiguity(cluster.workload());
            if (ambiguity.ambiguous()) {
                buildToWorkload = LinkStrength.AMBIGUOUS;
                reason = ambiguity.reason();
                limitations.add("Zeitnaehe und gleicher Image-Tag ergeben keinen exakten Nachweis.");
            } else {
                boolean imageMatch = imagesMatch(build, cluster);
                buildToWorkload = imageMatch ? LinkStrength.EXACT : LinkStrength.PROBABLE;
                reason = imageMatch
                        ? "Der Image-Digest/Tag des erfassten Pods stimmt mit der im Build verwendeten "
                                + "Image-Referenz ueberein."
                        : "Workload und Revision sind ueber die Owner-UID eindeutig, aber es liegt kein "
                                + "Image-Digest-Vergleich mit dem Build vor.";
                if (!imageMatch) {
                    limitations.add("Kein Image-Digest-Abgleich mit dem Jenkins-Lauf verfuegbar.");
                }
            }
        }
        links.add(new SnapshotLink(buildNode, workloadNode, "build-to-workload", buildToWorkload, reason,
                List.copyOf(refs), List.copyOf(limitations)));

        if (configuration != null) {
            LinkStrength buildToConfig = LinkStrength.UNLINKED;
            String configReason;
            if (revision.source() == RevisionProvenance.Source.JENKINS_SCM && revision.hasCommit()) {
                buildToConfig = LinkStrength.EXACT;
                configReason = "Vollstaendiger Commit aus strukturierten Jenkins-SCM-Daten zugeordnet.";
            } else if (revision.hasCommit()) {
                buildToConfig = LinkStrength.PROBABLE;
                configReason = "Commit ist " + revision.provenanceLevel()
                        + "; die Zuordnung ist nicht unabhaengig bestaetigt.";
            } else {
                configReason = revision.note().isBlank()
                        ? "Keine belastbare Revisionszuordnung."
                        : revision.note();
            }
            links.add(new SnapshotLink(buildNode, target.configurationNodeId(), "build-to-configuration",
                    buildToConfig, configReason,
                    evidenceRefs(evidence, EvidenceType.GIT_CONFIGURATION), List.of()));
        }
        if (consoleLog != null && consoleLog.truncated()) {
            limitations.add("Das Console-Log wurde nach Bytebudget abgeschnitten; das Buildende wurde nicht "
                    + "zwingend gelesen.");
        }
        if (build != null && build.isAborted()) {
            limitations.add("Der Build ist abgebrochen; das ist nicht automatisch ein Deploymentfehler.");
        }
        return List.copyOf(links);
    }

    /**
     * Vergleicht die im Jenkins-Lauf verwendeten Image-Angaben mit den Images der erfassten
     * Pods.
     *
     * <p>Ein Treffer liegt vor, wenn eine vollstaendige Referenz gleich ist oder wenn ein
     * <em>Tag</em> in den Buildparametern dem Tag der Pod-Image-Referenz entspricht. Zeitnaehe
     * allein ist kein Treffer; ohne Treffer bleibt der Link {@code probable}.</p>
     */
    private boolean imagesMatch(BuildSnapshot build, ClusterCollection cluster) {
        if (build == null || build.imageReferences().isEmpty() || cluster.workload() == null) {
            return false;
        }
        List<String> podImages = cluster.pods().stream()
                .flatMap(pod -> pod.containers().stream())
                .map(ContainerSnapshot::image)
                .filter(java.util.Objects::nonNull)
                .toList();
        for (String reference : build.imageReferences()) {
            for (String image : podImages) {
                if (image.equals(reference)) {
                    return true;
                }
                // Ein reiner Tag (ohne Repository und ohne Doppelpunkt) wird mit dem Tag der
                // Pod-Image-Referenz verglichen. Ein blosses "endsWith" waere zu grob: es wuerde
                // auch ein gleichnamiges Suffix ohne Tag-Grenze akzeptieren.
                if (reference.indexOf(':') < 0 && reference.indexOf('/') < 0) {
                    if (!tagOf(image).isEmpty() && tagOf(image).equals(reference)) {
                        return true;
                    }
                } else if (image.endsWith(":" + tagOf(reference)) && !tagOf(reference).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tag einer Image-Referenz; leer, wenn kein Tag angegeben ist. */
    private static String tagOf(String image) {
        int colon = image.lastIndexOf(':');
        int slash = image.lastIndexOf('/');
        return colon > slash ? image.substring(colon + 1) : "";
    }

    /** Hilfsmittel fuer Tests: alle Evidence-Arten eines Entwurfs. */
    static List<EvidenceType> types(List<RawEvidence> evidence) {
        return evidence.stream().map(RawEvidence::type).toList();
    }
}
