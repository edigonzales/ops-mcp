package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.ObjectRef;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest und schreibt Manifeste und den Evidence-Index.
 *
 * <p>Beim Lesen gelten Manifest und Evidence als nicht vertrauenswuerdig: Es werden nur die
 * erwarteten Felder uebernommen, unbekannte Werte fuehren zu einem
 * {@code ARTIFACT_INTEGRITY}-Fehler. Strukturierte Felder werden explizit abgebildet, nicht
 * ueber Reflexion oder Objektinstanziierung aus Daten.</p>
 */
public final class ManifestCodec {

    private final JsonMapper mapper;

    public ManifestCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(SnapshotManifest manifest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("snapshotId", manifest.snapshotId());
        root.put("useCase", manifest.useCase());
        root.put("dataMode", manifest.dataMode().wireName());
        root.put("applicationId", manifest.applicationId());
        root.put("environment", manifest.environment());
        ObjectNode build = root.putObject("buildRef");
        if (manifest.buildRef() != null) {
            build.put("instanceId", manifest.buildRef().instanceId());
            ArrayNode jobPath = build.putArray("jobPath");
            manifest.buildRef().jobPath().forEach(jobPath::add);
            build.put("buildNumber", manifest.buildRef().buildNumber());
            build.put("metadataAvailable", manifest.buildRef().metadataAvailable());
        }
        ObjectNode target = root.putObject("target");
        target.put("clusterId", manifest.clusterId());
        target.put("namespace", manifest.namespace());
        target.put("workloadKind", manifest.workloadKind());
        target.put("workloadName", manifest.workloadName());
        root.put("collectionStartedAt", iso(manifest.collectionStartedAt()));
        root.put("collectionFinishedAt", iso(manifest.collectionFinishedAt()));
        if (manifest.investigationWindow() != null) {
            ObjectNode window = root.putObject("investigationWindow");
            window.put("from", iso(manifest.investigationWindow().from()));
            window.put("to", iso(manifest.investigationWindow().to()));
            window.put("timeBasis", manifest.investigationWindow().timeBasis().wireName());
            window.put("derivation", manifest.investigationWindow().derivation());
        }
        root.put("collectionStatus", manifest.collectionStatus().wireName());
        root.set("sourceResults", mapper.valueToTree(manifest.sourceResults().stream()
                .map(SourceSummary::toMap).toList()));
        root.set("links", mapper.valueToTree(manifest.links().stream().map(SnapshotLink::toMap).toList()));
        root.set("evidenceIndex", mapper.valueToTree(manifest.evidenceIndex().stream()
                .map(EvidenceIndexEntry::toMap).toList()));
        root.set("redaction", mapper.valueToTree(manifest.redaction() == null
                ? RedactionSummary.empty(ch.so.agi.ops.security.Redactor.RULE_VERSION).toMap()
                : manifest.redaction().toMap()));
        root.set("toolVersions", mapper.valueToTree(manifest.toolVersions()));
        if (!manifest.warnings().isEmpty()) {
            root.set("warnings", mapper.valueToTree(manifest.warnings()));
        }
        if (!manifest.errors().isEmpty()) {
            root.set("errors", mapper.valueToTree(manifest.errors().stream()
                    .map(SourceSummary::errorToMap).toList()));
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    public SnapshotManifest read(JsonNode root, String expectedSnapshotId) {
        if (root == null || !root.isObject()) {
            throw new ArtifactIntegrityException("Manifest ist kein JSON-Objekt.");
        }
        int schemaVersion = intField(root, "schemaVersion", -1);
        if (schemaVersion != SnapshotManifest.SCHEMA_VERSION) {
            throw new ArtifactIntegrityException("Nicht unterstuetzte schemaVersion im Manifest: " + schemaVersion);
        }
        String snapshotId = textField(root, "snapshotId", null);
        if (snapshotId == null || !snapshotId.equals(expectedSnapshotId)) {
            throw new ArtifactIntegrityException("Manifest-Snapshot-ID passt nicht zum Verzeichnis.");
        }
        String useCase = textField(root, "useCase", null);
        DataMode dataMode = enumField(DataMode.class, DataMode::wireName, textField(root, "dataMode", null),
                "dataMode");
        String applicationId = textField(root, "applicationId", null);
        String environment = textField(root, "environment", null);

        SnapshotManifest.BuildRefView buildRef = null;
        JsonNode buildNode = root.get("buildRef");
        if (buildNode != null && buildNode.isObject()) {
            buildRef = new SnapshotManifest.BuildRefView(
                    textField(buildNode, "instanceId", null),
                    stringList(buildNode.get("jobPath")),
                    intField(buildNode, "buildNumber", 0),
                    booleanField(buildNode, "metadataAvailable", false));
        }

        JsonNode targetNode = root.get("target");
        String clusterId = targetNode == null ? null : textField(targetNode, "clusterId", null);
        String namespace = targetNode == null ? null : textField(targetNode, "namespace", null);
        String workloadKind = targetNode == null ? null : textField(targetNode, "workloadKind", null);
        String workloadName = targetNode == null ? null : textField(targetNode, "workloadName", null);

        Instant collectionStartedAt = instantField(root, "collectionStartedAt");
        Instant collectionFinishedAt = instantField(root, "collectionFinishedAt");

        InvestigationWindow window = null;
        JsonNode windowNode = root.get("investigationWindow");
        if (windowNode != null && windowNode.isObject()) {
            String basis = textField(windowNode, "timeBasis", "unknown");
            InvestigationWindow.TimeBasis timeBasis;
            try {
                timeBasis = java.util.Arrays.stream(InvestigationWindow.TimeBasis.values())
                        .filter(value -> value.wireName().equals(basis))
                        .findFirst()
                        .orElse(InvestigationWindow.TimeBasis.UNKNOWN);
            } catch (RuntimeException ex) {
                timeBasis = InvestigationWindow.TimeBasis.UNKNOWN;
            }
            window = new InvestigationWindow(instantField(windowNode, "from"), instantField(windowNode, "to"),
                    timeBasis, textField(windowNode, "derivation", ""));
        }

        CollectionStatus status = enumField(CollectionStatus.class, CollectionStatus::wireName,
                textField(root, "collectionStatus", null), "collectionStatus");

        List<SourceSummary> sources = new ArrayList<>();
        JsonNode sourcesNode = root.get("sourceResults");
        if (sourcesNode != null && sourcesNode.isArray()) {
            for (JsonNode source : sourcesNode) {
                sources.add(readSourceSummary(source));
            }
        }

        List<SnapshotLink> links = new ArrayList<>();
        JsonNode linksNode = root.get("links");
        if (linksNode != null && linksNode.isArray()) {
            for (JsonNode link : linksNode) {
                links.add(readLink(link));
            }
        }

        List<EvidenceIndexEntry> evidence = new ArrayList<>();
        JsonNode evidenceNode = root.get("evidenceIndex");
        if (evidenceNode == null || !evidenceNode.isArray()) {
            throw new ArtifactIntegrityException("Manifest enthaelt keinen Evidence-Index.");
        }
        for (JsonNode entry : evidenceNode) {
            evidence.add(readEvidenceEntry(entry, dataMode));
        }

        RedactionSummary redaction = null;
        JsonNode redactionNode = root.get("redaction");
        if (redactionNode != null && redactionNode.isObject()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            JsonNode countsNode = redactionNode.get("counts");
            if (countsNode != null && countsNode.isObject()) {
                countsNode.properties().forEach(entry -> counts.put(entry.getKey(), entry.getValue().asInt()));
            }
            redaction = new RedactionSummary(textField(redactionNode, "ruleVersion", "unbekannt"), counts,
                    intField(redactionNode, "totalCount", 0), stringList(redactionNode.get("limitations")));
        }

        Map<String, String> toolVersions = new LinkedHashMap<>();
        JsonNode versionsNode = root.get("toolVersions");
        if (versionsNode != null && versionsNode.isObject()) {
            versionsNode.properties().forEach(entry -> toolVersions.put(entry.getKey(), entry.getValue().asString()));
        }

        List<OpsError> errors = new ArrayList<>();
        JsonNode errorsNode = root.get("errors");
        if (errorsNode != null && errorsNode.isArray()) {
            for (JsonNode error : errorsNode) {
                errors.add(readError(error));
            }
        }

        return new SnapshotManifest(schemaVersion, snapshotId, useCase, dataMode, applicationId, environment,
                buildRef, clusterId, namespace, workloadKind, workloadName, collectionStartedAt,
                collectionFinishedAt, window, status, sources, links, evidence, redaction, toolVersions,
                stringList(root.get("warnings")), errors);
    }

    private SourceSummary readSourceSummary(JsonNode node) {
        SourceStatus status = enumField(SourceStatus.class, Enum::name, textField(node, "status", null), "status");
        List<OpsError> errors = new ArrayList<>();
        JsonNode errorsNode = node.get("errors");
        if (errorsNode != null && errorsNode.isArray()) {
            for (JsonNode error : errorsNode) {
                errors.add(readError(error));
            }
        }
        return new SourceSummary(textField(node, "sourceId", "unbekannt"), status,
                instantField(node, "collectedAt"), stringList(node.get("evidenceIds")),
                stringList(node.get("notes")), errors);
    }

    private SnapshotLink readLink(JsonNode node) {
        LinkStrength strength = enumField(LinkStrength.class, value -> value.name().toLowerCase(java.util.Locale.ROOT),
                textField(node, "strength", null), "strength");
        return new SnapshotLink(textField(node, "from", ""), textField(node, "to", ""),
                textField(node, "relation", ""), strength, textField(node, "reason", ""),
                stringList(node.get("evidenceRefs")), stringList(node.get("limitations")));
    }

    private EvidenceIndexEntry readEvidenceEntry(JsonNode node, DataMode fallbackMode) {
        String id = textField(node, "id", null);
        if (id == null || !id.matches("EV-[0-9]{4,}")) {
            throw new ArtifactIntegrityException("Unzulaessige Evidence-ID im Manifest.");
        }
        EvidenceType type = enumField(EvidenceType.class, EvidenceType::wireName, textField(node, "type", null), "type");
        ObjectRef object = null;
        JsonNode objectNode = node.get("object");
        if (objectNode != null && objectNode.isObject()) {
            object = new ObjectRef(textField(objectNode, "kind", "unbekannt"),
                    textField(objectNode, "namespace", ""), textField(objectNode, "name", ""),
                    textField(objectNode, "uid", null));
        }
        JsonNode locatorNode = node.get("locator");
        if (locatorNode == null || !locatorNode.isObject()) {
            throw new ArtifactIntegrityException("Evidence " + id + " hat keinen Locator.");
        }
        String path = textField(locatorNode, "path", null);
        int lineCount = intField(locatorNode, "lineCount", -1);
        if (path == null || lineCount < 1) {
            throw new ArtifactIntegrityException("Evidence " + id + " hat einen unzulaessigen Locator.");
        }
        String sha256 = textField(node, "sha256", null);
        long byteSize = longField(node, "byteSize", -1);
        if (sha256 == null || byteSize < 1) {
            throw new ArtifactIntegrityException("Evidence " + id + " hat keinen Hash oder keine Groesse.");
        }
        DataMode dataMode = fallbackMode;
        String modeValue = textField(node, "dataMode", null);
        if (modeValue != null) {
            try {
                dataMode = DataMode.fromWireName(modeValue);
            } catch (IllegalArgumentException ex) {
                throw new ArtifactIntegrityException("Evidence " + id + " hat einen unbekannten dataMode.");
            }
        }
        Locator locator = new Locator(textField(node, "provenance", path), null, null, null, false, "");
        return new EvidenceIndexEntry(id, type, textField(node, "sourceId", "unbekannt"), object,
                instantField(node, "observedAt"), instantField(node, "collectedAt"), path, sha256, lineCount,
                byteSize, locator, dataMode, stringList(node.get("derivedFrom")),
                textField(node, "description", ""), stringList(node.get("notes")));
    }

    private OpsError readError(JsonNode node) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(textField(node, "code", ErrorCode.INTERNAL_ERROR.name()));
        } catch (IllegalArgumentException ex) {
            code = ErrorCode.INTERNAL_ERROR;
        }
        return new OpsError(code, textField(node, "sourceId", "unbekannt"), textField(node, "message", ""),
                booleanField(node, "retryable", false), textField(node, "suggestedAction", ""));
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String textField(JsonNode node, String name, String fallback) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isString()) {
            return fallback;
        }
        return value.asString();
    }

    private static int intField(JsonNode node, String name, int fallback) {
        JsonNode value = node.get(name);
        return value == null || !value.isIntegralNumber() ? fallback : value.asInt();
    }

    private static long longField(JsonNode node, String name, long fallback) {
        JsonNode value = node.get(name);
        return value == null || !value.isIntegralNumber() ? fallback : value.asLong();
    }

    private static boolean booleanField(JsonNode node, String name, boolean fallback) {
        JsonNode value = node.get(name);
        return value == null || !value.isBoolean() ? fallback : value.asBoolean();
    }

    private static Instant instantField(JsonNode node, String name) {
        String value = textField(node, name, null);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ArtifactIntegrityException("Ungueltiger Zeitstempel im Manifest: " + name);
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isString()) {
                result.add(element.asString());
            }
        }
        return List.copyOf(result);
    }

    private static <E extends Enum<E>> E enumField(Class<E> type, java.util.function.Function<E, String> wireName,
            String value, String field) {
        if (value == null) {
            throw new ArtifactIntegrityException("Pflichtfeld fehlt im Manifest: " + field);
        }
        for (E constant : type.getEnumConstants()) {
            if (wireName.apply(constant).equals(value)) {
                return constant;
            }
        }
        throw new ArtifactIntegrityException("Unbekannter Wert im Manifest fuer " + field + ": "
                + ch.so.agi.ops.security.PathSafety.describe(value));
    }
}
