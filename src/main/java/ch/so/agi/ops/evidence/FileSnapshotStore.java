package ch.so.agi.ops.evidence;

import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.snapshot.EvidenceIndexEntry;
import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.snapshot.RedactionSummary;
import ch.so.agi.ops.snapshot.SnapshotDraft;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.SnapshotSummary;
import ch.so.agi.ops.snapshot.SourceSummary;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import ch.so.agi.ops.util.FilePermissions;
import ch.so.agi.ops.util.Hashes;
import ch.so.agi.ops.application.port.SnapshotStore;
import ch.so.agi.ops.snapshot.ManifestCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * Dateibasierter Snapshot-Store.
 *
 * <p>Ablage:</p>
 * <pre>
 * &lt;storageRoot&gt;/snapshots/&lt;snapshotId&gt;/
 *   manifest.json
 *   normalized.json
 *   evidence/EV-0001.txt
 * </pre>
 *
 * <p>Ein Snapshot wird atomar fertiggestellt: Es wird zuerst in ein temporaeres Verzeichnis
 * geschrieben und erst danach umbenannt. Nach einem Prozessabbruch ist kein halbfertiger
 * Snapshot als final sichtbar. Evidence-Dateien sind nach dem Schreiben unveraenderlich; es
 * werden niemals Rohdaten vor der Redaktion gespeichert.</p>
 */
public final class FileSnapshotStore implements SnapshotStore {

    public static final String SNAPSHOTS_DIR = "snapshots";
    public static final String EVIDENCE_DIR = "evidence";

    private final Path storageRoot;
    private final Clock clock;
    private final RandomGenerator random;
    private final JsonMapper mapper;
    private final ManifestCodec codec;
    private final JsonLineRenderer renderer;
    private final RedactorProfile redactorProfile;

    public FileSnapshotStore(Path storageRoot, Clock clock, RandomGenerator random, JsonMapper mapper) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.random = random;
        this.mapper = mapper;
        this.codec = new ManifestCodec(mapper);
        this.renderer = new JsonLineRenderer(mapper);
        this.redactorProfile = new RedactorProfile();
    }

    public Path storageRoot() {
        return storageRoot;
    }

    @Override
    public ch.so.agi.ops.snapshot.SnapshotSaveResult save(SnapshotDraft draft) {
        Instant now = clock.instant();
        SnapshotId id = nextId(now);
        Path snapshotsDir = storageRoot.resolve(SNAPSHOTS_DIR);
        Path target = snapshotsDir.resolve(id.value());
        Path scratch = null;
        try {
            FilePermissions.createDirectory(snapshotsDir);
            scratch = Files.createTempDirectory(snapshotsDir, ".incomplete-");
            FilePermissions.applyDirectory(scratch);
            Path evidenceDir = FilePermissions.createDirectory(scratch.resolve(EVIDENCE_DIR));

            List<EvidenceIndexEntry> index = new ArrayList<>();
            Map<String, String> idMapping = new LinkedHashMap<>();
            int counter = 1;
            for (RawEvidence raw : draft.evidence()) {
                String evidenceId = String.format("EV-%04d", counter++);
                idMapping.put(raw.id(), evidenceId);
                Redactor.Result redactedText = Redactor.redactLines(raw.contentLines());
                redactorProfile.add(redactedText.counts());
                String text = ensureTrailingNewline(redactedText.text());
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                String relativePath = EVIDENCE_DIR + "/" + evidenceId + ".txt";
                Path file = evidenceDir.resolve(evidenceId + ".txt");
                if (!file.normalize().startsWith(scratch)) {
                    throw new ArtifactIntegrityException("Evidence-Pfad verlaesst das Snapshotverzeichnis.");
                }
                FilePermissions.writeFile(file, bytes);
                int lineCount = raw.contentLines().isEmpty() ? 0 : raw.contentLines().size();
                Locator locator = raw.locator();
                List<String> notes = new ArrayList<>(raw.notes());
                if (redactedText.totalCount() > 0) {
                    notes.add("Redaktion angewendet; " + redactedText.totalCount()
                            + " Ersetzung(en). Originalwerte werden nicht aufbewahrt.");
                }
                index.add(new EvidenceIndexEntry(evidenceId, raw.type(), raw.sourceId(), raw.object(),
                        raw.observedAt(), now, relativePath, Hashes.sha256Hex(bytes), lineCount, bytes.length,
                        locator, draft.dataMode(), List.of(), raw.description(), notes));
            }

            List<SourceSummary> sources = rewriteSources(draft.sourceResults(), idMapping);
            List<ch.so.agi.ops.snapshot.SnapshotLink> links =
                    rewriteLinks(draft.links(), idMapping, draft.warnings());

            RedactionSummary redaction = new RedactionSummary(Redactor.RULE_VERSION, redactorProfile.counts(),
                    redactorProfile.total(), RedactionSummary.defaultLimitations());

            var manifest = new ch.so.agi.ops.snapshot.SnapshotManifest(
                    ch.so.agi.ops.snapshot.SnapshotManifest.SCHEMA_VERSION,
                    id.value(),
                    ch.so.agi.ops.snapshot.SnapshotManifest.USE_CASE_DEPLOYMENT_FAILURE,
                    draft.dataMode(),
                    draft.applicationId(),
                    draft.environment(),
                    new ch.so.agi.ops.snapshot.SnapshotManifest.BuildRefView(
                            draft.buildRef().instanceId(), draft.buildRef().jobPath(),
                            draft.buildRef().buildNumber(), draft.buildMetadataAvailable()),
                    draft.clusterId(),
                    draft.namespace(),
                    draft.workloadKind(),
                    draft.workloadName(),
                    draft.collectionStartedAt(),
                    draft.collectionFinishedAt(),
                    draft.investigationWindow(),
                    draft.collectionStatus(),
                    sources,
                    links,
                    index,
                    redaction,
                    draft.toolVersions(),
                    draft.warnings(),
                    draft.errors());

            writeAtomically(scratch.resolve("manifest.json"), codec.write(manifest).getBytes(StandardCharsets.UTF_8));
            writeAtomically(scratch.resolve("normalized.json"),
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(mapper.valueToTree(draft.normalizedData()))
                            .getBytes(StandardCharsets.UTF_8));

            Files.move(scratch, target, StandardCopyOption.ATOMIC_MOVE);
            FilePermissions.applyDirectory(target);
            return new ch.so.agi.ops.snapshot.SnapshotSaveResult(id, idMapping);
        } catch (IOException ex) {
            cleanup(scratch);
            throw new ArtifactIntegrityException("Snapshot konnte nicht gespeichert werden: " + ex.getMessage());
        } catch (RuntimeException ex) {
            cleanup(scratch);
            throw ex;
        }
    }

    private void cleanup(Path scratch) {
        if (scratch == null || !Files.exists(scratch)) {
            return;
        }
        try (var walk = Files.walk(scratch)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Aufraeumen ist best effort.
                }
            });
        } catch (IOException ignored) {
            // Aufraeumen ist best effort.
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".tmp-", ".json");
        FilePermissions.writeFile(temp, bytes);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        FilePermissions.applyFile(target);
    }

    private static String ensureTrailingNewline(String text) {
        if (text.isEmpty()) {
            return "\n";
        }
        return text.endsWith("\n") ? text : text + "\n";
    }

    private List<SourceSummary> rewriteSources(List<SourceSummary> sources, Map<String, String> mapping) {
        List<SourceSummary> result = new ArrayList<>(sources.size());
        for (SourceSummary source : sources) {
            List<String> ids = new ArrayList<>();
            for (String evidenceId : source.evidenceIds()) {
                String mapped = mapping.getOrDefault(evidenceId, evidenceId);
                if (mapped.startsWith("EV-")) {
                    ids.add(mapped);
                }
            }
            result.add(new SourceSummary(source.sourceId(), source.status(), source.collectedAt(), ids,
                    source.notes(), source.errors()));
        }
        return List.copyOf(result);
    }

    private List<ch.so.agi.ops.snapshot.SnapshotLink> rewriteLinks(List<ch.so.agi.ops.snapshot.SnapshotLink> links,
            Map<String, String> mapping, List<String> warnings) {
        List<ch.so.agi.ops.snapshot.SnapshotLink> result = new ArrayList<>(links.size());
        for (ch.so.agi.ops.snapshot.SnapshotLink link : links) {
            List<String> refs = new ArrayList<>();
            boolean dropped = false;
            for (String evidenceId : link.evidenceRefs()) {
                String mapped = mapping.get(evidenceId);
                if (mapped == null) {
                    if (evidenceId.startsWith("EV-")) {
                        mapped = evidenceId;
                    } else {
                        dropped = true;
                    }
                }
                if (mapped != null) {
                    refs.add(mapped);
                }
            }
            result.add(new ch.so.agi.ops.snapshot.SnapshotLink(link.from(), link.to(), link.relation(),
                    link.strength(), link.reason(), refs, link.limitations()));
        }
        return List.copyOf(result);
    }

    @Override
    public SnapshotSummary describe(SnapshotId id) {
        return SnapshotSummary.from(read(id).manifest());
    }

    @Override
    public StoredSnapshot read(SnapshotId id) {
        Path root = snapshotPath(id);
        if (!Files.isDirectory(root)) {
            throw new ArtifactIntegrityException("Snapshot nicht gefunden: " + id);
        }
        Path manifestFile = root.resolve("manifest.json");
        Path normalizedFile = root.resolve("normalized.json");
        if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(normalizedFile)) {
            throw new ArtifactIntegrityException("Snapshot ist unvollstaendig: " + id);
        }
        JsonNode manifestNode;
        JsonNode normalized;
        try {
            manifestNode = mapper.readTree(Files.readAllBytes(manifestFile));
            normalized = mapper.readTree(Files.readAllBytes(normalizedFile));
        } catch (IOException | RuntimeException ex) {
            throw new ArtifactIntegrityException("Snapshot ist nicht lesbar: " + id);
        }
        var manifest = codec.read(manifestNode, id.value());
        verifyEvidence(root, manifest);
        return new StoredSnapshot(manifest, normalized, root);
    }

    /** Prueft Pfadgrenzen, Groessen und Hashes der Evidence-Dateien. */
    private void verifyEvidence(Path root, ch.so.agi.ops.snapshot.SnapshotManifest manifest) {
        for (EvidenceIndexEntry entry : manifest.evidenceIndex()) {
            if (!ch.so.agi.ops.security.PathSafety.isSafeRelativePath(entry.path())) {
                throw new ArtifactIntegrityException("Evidence-Pfad ist unzulaessig: " + entry.id());
            }
            Path file = root.resolve(entry.path()).normalize();
            if (!file.startsWith(root)) {
                throw new ArtifactIntegrityException("Evidence-Pfad verlaesst den Snapshot: " + entry.id());
            }
            if (!Files.isRegularFile(file)) {
                throw new ArtifactIntegrityException("Evidence-Datei fehlt: " + entry.id());
            }
            try {
                long size = Files.size(file);
                if (size != entry.byteSize()) {
                    throw new ArtifactIntegrityException("Evidence-Datei hat eine unerwartete Groesse: " + entry.id());
                }
                String hash = Hashes.sha256Hex(file);
                if (!hash.equals(entry.sha256())) {
                    throw new ArtifactIntegrityException("Evidence-Datei wurde veraendert: " + entry.id());
                }
            } catch (IOException ex) {
                throw new ArtifactIntegrityException("Evidence-Datei ist nicht lesbar: " + entry.id());
            }
        }
    }

    @Override
    public EvidencePage readEvidence(SnapshotId id, String evidenceId, int startLine, int maxLines) {
        StoredSnapshot snapshot = read(id);
        EvidenceIndexEntry entry = snapshot.evidence(evidenceId);
        if (entry == null) {
            throw new ArtifactIntegrityException(ch.so.agi.ops.domain.ErrorCode.NOT_FOUND,
                    "Unbekannte Evidence-ID: " + ch.so.agi.ops.security.PathSafety.describe(evidenceId));
        }
        Path file = snapshot.rootPath().resolve(entry.path()).normalize();
        if (!file.startsWith(snapshot.rootPath())) {
            throw new ArtifactIntegrityException("Evidence-Pfad verlaesst den Snapshot: " + evidenceId);
        }
        List<String> allLines;
        try {
            allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ArtifactIntegrityException("Evidence-Datei ist nicht lesbar: " + evidenceId);
        }
        int total = allLines.size();
        int from = Math.max(1, startLine);
        int limit = Math.max(1, Math.min(maxLines, ch.so.agi.ops.config.Limits.HARD_MAX_EVIDENCE_LINES));
        List<String> page = new ArrayList<>();
        boolean truncatedLine = false;
        int maxLineLength = 4000;
        for (int i = from - 1; i < total && page.size() < limit; i++) {
            String line = allLines.get(i);
            if (line.length() > maxLineLength) {
                line = line.substring(0, maxLineLength) + " ...[Zeile gekuerzt]";
                truncatedLine = true;
            }
            page.add(line);
        }
        int endLine = page.isEmpty() ? from - 1 : from + page.size() - 1;
        boolean hasMore = endLine < total;
        List<String> notes = new ArrayList<>(entry.notes());
        if (truncatedLine) {
            notes.add("Eine ueberlange Einzelzeile wurde gekuerzt.");
        }
        return new EvidencePage(evidenceId, entry.type(), entry.sourceId(), page, from, endLine, total, hasMore,
                hasMore ? endLine + 1 : null, truncatedLine, false, notes);
    }

    @Override
    public boolean exists(SnapshotId id) {
        return Files.isDirectory(snapshotPath(id));
    }

    @Override
    public List<SnapshotId> list() {
        Path snapshotsDir = storageRoot.resolve(SNAPSHOTS_DIR);
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }
        List<SnapshotId> ids = new ArrayList<>();
        try (var stream = Files.list(snapshotsDir)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(name -> {
                        try {
                            ids.add(SnapshotId.of(name));
                        } catch (IllegalArgumentException ignored) {
                            // Fremde Verzeichnisse werden nicht als Snapshot interpretiert.
                        }
                    });
        } catch (IOException ex) {
            return List.of();
        }
        return List.copyOf(ids);
    }

    private SnapshotId nextId(Instant now) {
        for (int attempt = 0; attempt < 20; attempt++) {
            SnapshotId candidate = SnapshotId.generate(now, random);
            if (!Files.exists(snapshotPath(candidate))) {
                return candidate;
            }
        }
        throw new ArtifactIntegrityException("Es konnte keine freie Snapshot-ID erzeugt werden.");
    }

    private Path snapshotPath(SnapshotId id) {
        Path path = storageRoot.resolve(SNAPSHOTS_DIR).resolve(id.value()).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new ArtifactIntegrityException("Snapshot-Pfad verlaesst den Ausgabebereich.");
        }
        return path;
    }

    /** Sammelt Redaktionszaehler ueber alle Evidence-Dateien eines Snapshots. */
    private static final class RedactorProfile {
        private final Map<String, Integer> counts = new TreeMap<>();
        private int total;

        void add(Map<String, Integer> additions) {
            additions.forEach((key, value) -> counts.merge(key, value, Integer::sum));
            total = counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        Map<String, Integer> counts() {
            return Map.copyOf(counts);
        }

        int total() {
            return total;
        }
    }
}
