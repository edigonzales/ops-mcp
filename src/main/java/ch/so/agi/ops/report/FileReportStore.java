package ch.so.agi.ops.report;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.security.PathSafety;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dateibasierte Ablage validierter Berichte.
 *
 * <p>Ablage ist ausschliesslich {@code <storageRoot>/reports/<snapshotId>/<reportId>/} mit
 * {@code diagnosis.json} und {@code diagnosis.md}. Ein beliebiger Dateipfad ist nicht
 * zulaessig; ein vorhandener Bericht wird nie ueberschrieben. Verzeichnisse werden mit
 * {@code 0700}, Dateien mit {@code 0600} angelegt und atomar ueber eine temporaere Datei im
 * Zielverzeichnis fertiggestellt.</p>
 *
 * <p>Vor dem Schreiben wird der Bericht erneut redigiert; die Zaehler erscheinen als Warnung
 * am Validierungsergebnis. Ein ungueltiger Bericht wird abgewiesen, ohne dass etwas
 * geschrieben wird.</p>
 */
public final class FileReportStore implements ReportStore {

    /** Unterverzeichnis fuer Berichte, relativ zum storageRoot. */
    public static final String REPORTS_DIRECTORY = "reports";

    public static final String JSON_FILE_NAME = "diagnosis.json";
    public static final String MARKDOWN_FILE_NAME = "diagnosis.md";

    /** Praefix der Berichts-ID; die ID ist pfadsicher. */
    public static final String REPORT_ID_PREFIX = "RP-";
    public static final int REPORT_ID_LENGTH = 8;

    private static final String REPORT_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern REPORT_ID_PATTERN = Pattern.compile("RP-[a-z0-9]{8}");
    private static final int MAX_ID_ATTEMPTS = 32;
    private static final String TEMP_PREFIX = ".diagnosis-";
    private static final String TEMP_SUFFIX = ".tmp";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    /** Deterministische JSON-Ausgabe mit festem Zeilenumbruch, unabhaengig vom Betriebssystem. */
    private static final ObjectWriter JSON_WRITER = createJsonWriter();

    private final Path storageRoot;
    private final Clock clock;
    private final RandomGenerator random;
    private final boolean posix;
    private final DiagnosisValidator validator = new DiagnosisValidator();
    private final DiagnosisMarkdownRenderer renderer = new DiagnosisMarkdownRenderer();

    public FileReportStore(Path storageRoot, Clock clock, RandomGenerator random) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.posix = detectPosix(this.storageRoot);
    }

    @Override
    public StoredReport save(StoredSnapshot snapshot, DiagnosisDocument document) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.manifest() == null) {
            throw new ReportException(ErrorCode.REPORT_INVALID, "Der gebundene Snapshot hat kein Manifest.");
        }
        ReportValidationResult validation = validator.validate(document, snapshot);
        if (!validation.valid()) {
            throw invalidReport(validation);
        }
        SnapshotId snapshotId = toSnapshotId(snapshot.manifest().snapshotId());

        Redaction redaction = Redaction.apply(document);
        ReportValidationResult recheck = validator.validate(redaction.document(), snapshot);
        if (!recheck.valid()) {
            throw new ReportException(ErrorCode.REPORT_INVALID,
                    "Der Bericht ist nach der Redaktion nicht mehr gueltig.", recheck.errors());
        }
        List<String> warnings = new ArrayList<>(validation.warnings());
        warnings.addAll(redaction.warnings());
        ReportValidationResult storedValidation = ReportValidationResult.ok(warnings);

        byte[] json = serialize(redaction.document());
        byte[] markdown = renderer.render(redaction.document(), snapshot).getBytes(StandardCharsets.UTF_8);

        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String reportId = REPORT_ID_PREFIX + randomSuffix();
            String relativeDirectory = relativeDirectory(snapshotId.value(), reportId);
            Path directory = PathSafety.resolveWithin(storageRoot, relativeDirectory);
            if (Files.exists(directory)) {
                continue;
            }
            createDirectories(directory);
            Path jsonPath = directory.resolve(JSON_FILE_NAME);
            Path markdownPath = directory.resolve(MARKDOWN_FILE_NAME);
            try {
                writeAtomically(jsonPath, json);
                writeAtomically(markdownPath, markdown);
            } catch (UncheckedIOException e) {
                // Kein halbfertiger Bericht: beide Dateien und das Verzeichnis wieder entfernen.
                deleteQuietly(jsonPath);
                deleteQuietly(markdownPath);
                deleteQuietly(directory);
                throw e;
            }
            return new StoredReport(
                    reportId,
                    snapshotId.value(),
                    relativeDirectory + "/" + JSON_FILE_NAME,
                    relativeDirectory + "/" + MARKDOWN_FILE_NAME,
                    sha256(json),
                    sha256(markdown),
                    clock.instant(),
                    storedValidation);
        }
        throw new ReportException(ErrorCode.REPORT_INVALID,
                "Es konnte keine freie Berichts-ID erzeugt werden.");
    }

    @Override
    public DiagnosisDocument read(SnapshotId snapshotId, String reportId) {
        return readInternal(snapshotId, reportId, null);
    }

    /**
     * Liest einen gespeicherten Bericht und validiert ihn zusaetzlich vollstaendig gegen den
     * uebergebenen Snapshot (Belegreferenzen, Zeilenbereiche und Statusregeln). Die
     * Schnittstellenmethode {@link #read(SnapshotId, String)} prueft Struktur und
     * Snapshotbindung, weil die Persistenz keinen Zugriff auf den Snapshotbestand hat.
     */
    public DiagnosisDocument read(SnapshotId snapshotId, String reportId, StoredSnapshot snapshot) {
        return readInternal(snapshotId, reportId, snapshot);
    }

    @Override
    public List<StoredReport> list(SnapshotId snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Path directory = PathSafety.resolveWithin(storageRoot,
                REPORTS_DIRECTORY + "/" + snapshotId.value());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> reportIds = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isDirectory)
                    .map(entry -> entry.getFileName().toString())
                    .filter(REPORT_ID_PATTERN.asMatchPredicate())
                    .sorted()
                    .forEach(reportIds::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Der Berichtsordner konnte nicht gelesen werden.", e);
        }
        List<StoredReport> reports = new ArrayList<>(reportIds.size());
        for (String reportId : reportIds) {
            StoredReport report = describe(snapshotId, reportId);
            if (report != null) {
                reports.add(report);
            }
        }
        return List.copyOf(reports);
    }

    private DiagnosisDocument readInternal(SnapshotId snapshotId, String reportId, StoredSnapshot snapshot) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Path jsonPath = reportFile(snapshotId.value(), reportId, JSON_FILE_NAME);
        byte[] json = readBytes(jsonPath);
        DiagnosisDocument document = DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES);
        ReportValidationResult validation = snapshot == null
                ? validator.validateBinding(document, snapshotId)
                : validator.validate(document, snapshot);
        if (!validation.valid()) {
            throw invalidReport(validation);
        }
        return document;
    }

    /** Liest die Metadaten eines gespeicherten Berichts; {@code null}, wenn er nicht existiert. */
    private StoredReport describe(SnapshotId snapshotId, String reportId) {
        String relativeDirectory = relativeDirectory(snapshotId.value(), reportId);
        Path directory = PathSafety.resolveWithin(storageRoot, relativeDirectory);
        Path jsonPath = directory.resolve(JSON_FILE_NAME);
        Path markdownPath = directory.resolve(MARKDOWN_FILE_NAME);
        if (!Files.isRegularFile(jsonPath)) {
            return null;
        }
        byte[] json = readBytes(jsonPath);
        byte[] markdown = Files.isRegularFile(markdownPath) ? readBytes(markdownPath) : null;
        ReportValidationResult validation;
        try {
            DiagnosisDocument document = DiagnosisParser.parse(json, DiagnosisDocument.MAX_DRAFT_BYTES);
            validation = validator.validateBinding(document, snapshotId);
        } catch (ReportException e) {
            List<OpsError> errors = e.errors().isEmpty()
                    ? List.of(ReportValidationResult.error("diagnosis", e.getMessage()))
                    : e.errors();
            validation = ReportValidationResult.failed(errors, List.of());
        }
        return new StoredReport(
                reportId,
                snapshotId.value(),
                relativeDirectory + "/" + JSON_FILE_NAME,
                markdown == null ? null : relativeDirectory + "/" + MARKDOWN_FILE_NAME,
                sha256(json),
                markdown == null ? null : sha256(markdown),
                lastModified(jsonPath),
                validation);
    }

    private Path reportFile(String snapshotId, String reportId, String fileName) {
        if (reportId == null || !REPORT_ID_PATTERN.matcher(reportId).matches()) {
            throw new ReportException(ErrorCode.SCOPE_DENIED, "Unzulaessige Berichts-ID: "
                    + PathSafety.describe(reportId));
        }
        toSnapshotId(snapshotId);
        return PathSafety.resolveWithin(storageRoot,
                relativeDirectory(snapshotId, reportId) + "/" + fileName);
    }

    private static String relativeDirectory(String snapshotId, String reportId) {
        return REPORTS_DIRECTORY + "/" + snapshotId + "/" + reportId;
    }

    private static SnapshotId toSnapshotId(String snapshotId) {
        try {
            return SnapshotId.of(snapshotId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ReportException(ErrorCode.SCOPE_DENIED,
                    "Unzulaessige Snapshot-ID: " + PathSafety.describe(snapshotId));
        }
    }

    private static ReportException invalidReport(ReportValidationResult validation) {
        String message = validation.errors().isEmpty()
                ? "Der Bericht ist ungueltig."
                : validation.errors().getFirst().message();
        return new ReportException(ErrorCode.REPORT_INVALID, message, validation.errors());
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(REPORT_ID_LENGTH);
        for (int i = 0; i < REPORT_ID_LENGTH; i++) {
            sb.append(REPORT_ID_ALPHABET.charAt(random.nextInt(REPORT_ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private byte[] serialize(DiagnosisDocument document) {
        String json = JSON_WRITER.writeValueAsString(document.toMap());
        return (json.endsWith("\n") ? json : json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void createDirectories(Path directory) {
        try {
            if (posix) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectories(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Das Berichtsverzeichnis konnte nicht angelegt werden: "
                    + directory.getFileName(), e);
        }
        restrictDirectoryChain(directory);
    }

    /** Setzt {@code 0700} fuer die neu angelegten Verzeichnisse bis unterhalb des storageRoot. */
    private void restrictDirectoryChain(Path directory) {
        if (!posix) {
            return;
        }
        Path current = directory;
        while (current != null && current.startsWith(storageRoot) && !current.equals(storageRoot)) {
            setPermissions(current, DIRECTORY_PERMISSIONS);
            current = current.getParent();
        }
    }

    private void writeAtomically(Path target, byte[] content) {
        Path directory = target.getParent();
        Path temporary = null;
        try {
            temporary = createTemporaryFile(directory);
            Files.write(temporary, content);
            setPermissions(temporary, FILE_PERMISSIONS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            temporary = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Der Bericht konnte nicht geschrieben werden: "
                    + target.getFileName(), e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private Path createTemporaryFile(Path directory) throws IOException {
        if (posix) {
            return Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX,
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        return Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX);
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        if (!posix) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            // Dateisystem ohne POSIX-Rechte: die Rechtepruefung entfaellt, der Bericht bleibt lesbar.
        } catch (IOException e) {
            throw new UncheckedIOException("Die Dateirechte konnten nicht gesetzt werden.", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Aufraeumen ist best effort; der eigentliche Fehler wurde bereits gemeldet.
        }
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ReportException(ErrorCode.NOT_FOUND,
                    "Der Bericht ist nicht lesbar: " + PathSafety.describe(relativeToRoot(path)));
        }
    }

    private Instant lastModified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private String relativeToRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(storageRoot)
                ? storageRoot.relativize(normalized).toString().replace('\\', '/')
                : path.getFileName().toString();
    }

    private static boolean detectPosix(Path root) {
        Path probe = root;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return false;
        }
        try {
            return Files.getFileStore(probe).supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                sb.append(Character.forDigit((value >> 4) & 0xf, 16));
                sb.append(Character.forDigit(value & 0xf, 16));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfuegbar.", e);
        }
    }

    private static ObjectWriter createJsonWriter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return YamlSupport.jsonMapper().writerWithDefaultPrettyPrinter().with(printer);
    }

    /**
     * Redaktion aller Textfelder vor dem Speichern. Kennungen und Belegreferenzen bleiben
     * unveraendert, damit die Referenzintegritaet erhalten bleibt.
     */
    private static final class Redaction {

        private final DiagnosisDocument document;
        private final List<String> warnings;

        private Redaction(DiagnosisDocument document, List<String> warnings) {
            this.document = document;
            this.warnings = List.copyOf(warnings);
        }

        static Redaction apply(DiagnosisDocument document) {
            Counts counts = new Counts();
            DiagnosisDocument redacted = new DiagnosisDocument(
                    document.schemaVersion(),
                    document.snapshotId(),
                    counts.apply(document.language()),
                    document.assessment(),
                    counts.apply(document.title()),
                    counts.apply(document.summary()),
                    document.facts().stream()
                            .map(fact -> new DiagnosisDocument.Fact(fact.id(), counts.apply(fact.statement()),
                                    fact.evidence()))
                            .toList(),
                    document.hypotheses().stream()
                            .map(hypothesis -> new DiagnosisDocument.Hypothesis(
                                    hypothesis.id(),
                                    counts.apply(hypothesis.category()),
                                    counts.apply(hypothesis.statement()),
                                    hypothesis.strength(),
                                    hypothesis.supports(),
                                    hypothesis.counterEvidence(),
                                    hypothesis.limitations().stream().map(counts::apply).toList()))
                            .toList(),
                    document.timeline().stream()
                            .map(entry -> new DiagnosisDocument.TimelineEntry(
                                    entry.at(),
                                    entry.timeBasis(),
                                    counts.apply(entry.description()),
                                    entry.evidence()))
                            .toList(),
                    document.recommendations().stream()
                            .map(recommendation -> new DiagnosisDocument.Recommendation(
                                    counts.apply(recommendation.description()),
                                    counts.apply(recommendation.rationale()),
                                    recommendation.basedOnHypotheses(),
                                    counts.apply(recommendation.risk()),
                                    counts.apply(recommendation.verification()),
                                    recommendation.executed()))
                            .toList(),
                    document.openQuestions().stream().map(counts::apply).toList(),
                    document.limitations().stream().map(counts::apply).toList(),
                    document.actionsTaken().stream().map(counts::apply).toList());
            return new Redaction(redacted, counts.warnings());
        }

        DiagnosisDocument document() {
            return document;
        }

        List<String> warnings() {
            return warnings;
        }
    }

    /** Zaehlt die Ersetzungen der Redaktion ueber alle Textfelder. */
    private static final class Counts {

        private final Map<String, Integer> ruleCounts = new LinkedHashMap<>();
        private int total;

        String apply(String text) {
            if (text == null) {
                return null;
            }
            Redactor.Result result = Redactor.redact(text);
            if (result.totalCount() > 0) {
                total += result.totalCount();
                result.counts().forEach((rule, count) -> ruleCounts.merge(rule, count, Integer::sum));
            }
            return result.text();
        }

        List<String> warnings() {
            if (total == 0) {
                return List.of();
            }
            return List.of("Der Bericht wurde vor dem Speichern erneut redigiert: " + total
                    + " Ersetzung(en) durch Regeln " + ruleCounts + ".");
        }
    }
}
