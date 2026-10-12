package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.ConfigValue;
import ch.so.agi.ops.domain.git.ContainerConfig;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import ch.so.agi.ops.security.PathSafety;
import ch.so.agi.ops.security.ScopeViolationException;
import ch.so.agi.ops.util.DefaultProcessRunner;
import ch.so.agi.ops.util.Hashes;
import ch.so.agi.ops.util.ProcessRunner;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produktiver {@link ConfigurationReader} fuer einen lokalen Git-Checkout.
 *
 * <p>Der Adapter liest ausschliesslich die in der Konfiguration erlaubten Dateien an dem
 * belegten Commit. Fehlt der Commit lokal, meldet er {@code REVISION_UNAVAILABLE} und
 * verwendet <em>nicht</em> stillschweigend {@code HEAD} oder {@code main}. Der Arbeitsbaum
 * wird nie veraendert: es gibt keinen Checkout, keinen Fetch, keinen Pull und keinen Clone.</p>
 *
 * <p>Erlaubt ist die Schnittmenge aus {@code repositories.<id>.allowedFiles} und den in der
 * Anwendungsumgebung konfigurierten {@code configuration.templateFiles} bzw.
 * {@code parameterFiles}. Eine nicht erlaubte Datei erzeugt einen {@code SCOPE_DENIED}-Fehler
 * und {@code FileReadResult.status=NOT_ALLOWED} - ohne Leseversuch. Symlinks am Commit
 * (Baumodus {@code 120000}) und Pfade, die aus dem Checkout herauszeigen, werden nicht
 * gelesen.</p>
 *
 * <p>Der Produktivpfad verwendet {@link DefaultProcessRunner} mit der eingeschraenkten
 * Basisumgebung aus {@link GitProcess#restrictedBaseEnvironment()}.</p>
 *
 * <p>Der heutige Arbeitsbaumstand wird bewusst nicht zusaetzlich gelesen: es entstehen keine
 * {@code current_reference_only}-Dateien, {@code ResolvedConfiguration.currentReferenceFiles}
 * bleibt leer. Eine zusaetzliche Referenz darf nie als historischer Sollzustand gelten; V1
 * liefert sie deshalb gar nicht erst.</p>
 */
public final class LocalGitConfigurationReader implements ConfigurationReader {

    /** Timeout eines einzelnen Git-Aufrufs, soweit das Gesamtbudget mehr zulaesst. */
    public static final Duration STEP_TIMEOUT = Duration.ofSeconds(10);

    /** Ausgabebudget eines einzelnen Git-Aufrufs. */
    public static final long MAX_OUTPUT_BYTES = 8L * 1024 * 1024;

    /** Git-Aufrufe je gelesener Datei: Symlinkpruefung, Pfadexistenz, Inhalt. */
    public static final int GIT_CALLS_PER_FILE = 3;

    /** Git-Aufrufe der Revisionspruefung: {@code cat-file -e <commit>^{commit}}. */
    public static final int GIT_CALLS_PER_REVISION = 1;

    /** Quellekennung, wenn die Konfiguration mehrere Repositories kennt und keines gebunden ist. */
    public static final String GENERIC_SOURCE_ID = "git-configuration";

    private final OpsConfig config;
    private final ProcessRunner runner;
    private final Clock clock;
    private final JsonMapper mapper;
    private final TemplateParameterResolver resolver = new TemplateParameterResolver();
    private final String repositoryIdOverride;

    public LocalGitConfigurationReader(OpsConfig config, ProcessRunner runner, Clock clock, JsonMapper mapper) {
        this(config, null, runner, clock, mapper);
    }

    /**
     * Wie der vierstellige Konstruktor, bindet aber die Quellekennung an ein bestimmtes
     * Repository aus der Konfiguration.
     */
    public LocalGitConfigurationReader(OpsConfig config, String repositoryId, ProcessRunner runner, Clock clock,
            JsonMapper mapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.repositoryIdOverride = repositoryId == null || repositoryId.isBlank() ? null : repositoryId;
    }

    /** Produktivpfad: echter Prozessrunner mit eingeschraenkter Basisumgebung. */
    public static LocalGitConfigurationReader production(OpsConfig config, Clock clock, JsonMapper mapper) {
        return new LocalGitConfigurationReader(config, new DefaultProcessRunner(
                GitProcess.restrictedBaseEnvironment()), clock, mapper);
    }

    @Override
    public String sourceId() {
        if (repositoryIdOverride != null) {
            return repositoryIdOverride;
        }
        if (config.repositories().size() == 1) {
            return config.repositories().keySet().iterator().next();
        }
        return GENERIC_SOURCE_ID;
    }

    /**
     * Diagnosewert: aktueller {@code HEAD} des konfigurierten Checkouts. Niemals ein Ersatz
     * fuer eine fehlende belegte Revision.
     */
    public String headCommit() {
        RepositoryDefinition repository = firstRepository();
        if (repository == null || repository.localPath() == null || !isRepository(repository.localPath())) {
            return null;
        }
        try {
            GitObjectReader objects = new GitObjectReader(new GitProcess(repository.localPath(), runner,
                    STEP_TIMEOUT, MAX_OUTPUT_BYTES));
            return objects.resolveHead();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public SourceResult<ResolvedConfiguration> collect(ResolvedTarget target, RevisionProvenance revision,
            CollectionBudget budget) {
        Instant now = clock.instant();
        String sourceId = sourceId();
        List<OpsError> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        ConfigurationDefinition configuration = target == null || target.definition() == null
                ? null
                : target.definition().configuration();
        RepositoryDefinition repository = configuration == null
                ? null
                : config.repositories().get(configuration.repository());

        if (configuration == null) {
            errors.add(new OpsError(ErrorCode.CONFIG_INCOMPLETE, sourceId,
                    "Der Umgebung fehlt die Konfigurationszuordnung (configuration); es wird nichts gelesen.",
                    false, "configuration.templateFiles/parameterFiles in der lokalen Konfiguration ergaenzen."));
            return unavailable(sourceId, null, revision, List.of(), now, notes, errors, SourceStatus.NOT_ALLOWED);
        }
        if (repository == null) {
            errors.add(new OpsError(ErrorCode.CONFIG_INCOMPLETE, sourceId,
                    "Das referenzierte Repository '" + configuration.repository()
                            + "' ist nicht in repositories konfiguriert; es wird nichts gelesen.",
                    false, "repositories.<id> mit repositoryId, localPath und allowedFiles ergaenzen."));
            return unavailable(sourceId, null, revision, List.of(), now, notes, errors, SourceStatus.NOT_ALLOWED);
        }
        if (repository.localPath() == null) {
            errors.add(new OpsError(ErrorCode.CONFIG_INCOMPLETE, sourceId,
                    "Fuer das Repository '" + configuration.repository()
                            + "' ist kein lokaler Checkout konfiguriert; es wird nichts gelesen.",
                    false, "Vom Betreiber vorbereiteten lokalen Checkout in repositories.<id>.localPath eintragen."));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.NOT_ALLOWED);
        }

        Path root = repository.localPath().toAbsolutePath().normalize();
        String localPath = repository.localPath().toString();
        if (!isRepository(root)) {
            errors.add(new OpsError(ErrorCode.CONFIG_INVALID, sourceId,
                    "Der konfigurierte lokale Pfad ist kein Git-Checkout (kein .git und kein HEAD gefunden).",
                    false, "localPath pruefen; der Agent legt kein Repository an und klont nicht."));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.UNREACHABLE);
        }

        // 1) Revision pruefen, bevor irgendeine Datei gelesen wird.
        if (revision == null || !revision.hasCommit()) {
            return revisionGap(sourceId, repository, revision, now, notes, errors);
        }
        String commit = revision.commit();
        if (!GitProcess.isValidCommit(commit)) {
            errors.add(new OpsError(ErrorCode.SCOPE_DENIED, sourceId,
                    "Die uebergebene Revisionsangabe ist kein Hex-Commit und wird abgewiesen; "
                            + "es wird nichts gelesen.",
                    false, "Revision aus strukturierten Builddaten oder einer Betreiberangabe beziehen."));
            errors.add(new OpsError(ErrorCode.REVISION_UNVERIFIED, sourceId,
                    "Die Revisionsangabe ist nicht als vollstaendiger Commit belegt.", false,
                    "Buildmetadaten oder Betreiberangabe mit vollstaendigem Commit verwenden."));
            notes.add("Es wird kein Ersatz durch HEAD, main oder einen anderen Commit verwendet.");
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.NOT_ALLOWED);
        }
        if (revision.repositoryId() != null && !revision.repositoryId().isBlank()
                && !revision.repositoryId().equalsIgnoreCase(repository.repositoryId())) {
            errors.add(new OpsError(ErrorCode.SCOPE_DENIED, sourceId,
                    "Die Revision gehoert zum Repository '" + revision.repositoryId()
                            + "', gelesen wird '" + repository.repositoryId() + "'; es wird nichts gelesen.",
                    false, "Revision und Repositoryzuordnung pruefen; kein Repo-uebergreifender Ersatz."));
            errors.add(new OpsError(ErrorCode.REVISION_UNVERIFIED, sourceId,
                    "Die Revision ist dem konfigurierten Repository nicht zugeordnet.", false,
                    "Jenkins-SCM-Daten des passenden Repositories auswerten."));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.NOT_ALLOWED);
        }

        GitObjectReader objects;
        try {
            objects = readerFor(root, budget, now);
        } catch (GitReadException ex) {
            errors.add(ex.toError(sourceId));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.UNREACHABLE);
        }
        if (objects == null) {
            errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, sourceId,
                    "Das Erfassungsbudget ist vor dem Git-Lesezugriff abgelaufen; es wurde nichts gelesen.",
                    false, "collectTimeoutSeconds erhoehen oder spaeter erneut erfassen."));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.TRUNCATED);
        }
        if (!consumeRequests(budget, GIT_CALLS_PER_REVISION, sourceId, errors)) {
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.TRUNCATED);
        }
        try {
            if (!objects.commitExists(commit)) {
                errors.add(new OpsError(ErrorCode.REVISION_UNAVAILABLE, sourceId,
                        "Der belegte Commit ist im lokalen Checkout nicht vorhanden: " + shortOf(commit)
                                + ". Es wird kein anderer Commit als Ersatz gelesen.",
                        false, "Checkout auf den belegten Commit aktualisieren (manuell, ausserhalb des Agenten)."));
                notes.add("Kein Ersatz durch HEAD oder einen anderen Commit.");
                return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                        SourceStatus.NOT_PRESENT);
            }
        } catch (GitReadException ex) {
            errors.add(ex.toError(sourceId));
            return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                    SourceStatus.UNREACHABLE);
        }

        // 2) Erlaubte Dateien lesen. Kein Leseversuch ohne Allowlist-Treffer.
        List<String> configured = dedupe(configuration.allConfiguredFiles());
        int maxFiles = Math.max(0, config.limits().maxGitFiles());
        List<FileReadResult> files = new ArrayList<>();
        Map<String, String> templateContents = new LinkedHashMap<>();
        Map<String, String> parameterContents = new LinkedHashMap<>();
        List<String> skippedByFileLimit = new ArrayList<>();
        List<String> skippedByTime = new ArrayList<>();
        Set<String> erroredPaths = new HashSet<>();
        int reads = 0;
        int exhaustionsBefore = budget == null ? 0 : budget.exhaustions().size();

        for (String path : configured) {
            FileReadResult.FileRole role = roleOf(path, configuration);
            if (!GitProcess.isValidRelativePath(path)) {
                files.add(new FileReadResult(path, role, FileReadResult.FileStatus.NOT_ALLOWED, List.of(), null,
                        null, commit, "Unzulaessiger relativer Pfad; es wurde nicht gelesen."));
                errors.add(PathSafety.scopeDenied(sourceId,
                        "Unzulaessiger Dateipfad in der Konfiguration: " + PathSafety.describe(path)));
                erroredPaths.add(path);
                continue;
            }
            if (!repository.fileAllowed(path)) {
                files.add(new FileReadResult(path, role, FileReadResult.FileStatus.NOT_ALLOWED, List.of(), null,
                        null, commit, "Datei steht nicht in repositories." + repository.id()
                                + ".allowedFiles; es wurde nicht gelesen."));
                errors.add(PathSafety.scopeDenied(sourceId,
                        "Datei ist nicht in der Repository-Allowlist: " + PathSafety.describe(path)));
                erroredPaths.add(path);
                continue;
            }
            if (worktreeEscapes(root, path)) {
                files.add(new FileReadResult(path, role, FileReadResult.FileStatus.NOT_ALLOWED, List.of(), null,
                        null, commit, "Der Pfad zeigt als Symlink aus dem Checkout heraus; es wurde nicht gelesen."));
                errors.add(PathSafety.scopeDenied(sourceId,
                        "Symlink verlaesst den Checkout: " + PathSafety.describe(path)));
                erroredPaths.add(path);
                continue;
            }
            if (reads >= maxFiles) {
                skippedByFileLimit.add(path);
                continue;
            }
            if (budget != null && budget.timeExhausted(clock.instant())) {
                skippedByTime.add(path);
                continue;
            }
            GitObjectReader fileReader;
            try {
                fileReader = readerFor(root, budget, clock.instant());
            } catch (GitReadException ex) {
                errors.add(ex.toError(sourceId));
                skippedByTime.add(path);
                continue;
            }
            if (fileReader == null) {
                skippedByTime.add(path);
                continue;
            }
            if (!consumeRequests(budget, GIT_CALLS_PER_FILE, sourceId, errors)) {
                skippedByTime.add(path);
                break;
            }
            reads++;
            ReadOutcome outcome = readFile(fileReader, commit, path, role, budget, sourceId, errors, notes,
                    erroredPaths);
            files.add(outcome.result());
            if (outcome.content() != null) {
                if (role == FileReadResult.FileRole.PARAMETERS) {
                    parameterContents.put(path, outcome.content());
                } else {
                    templateContents.put(path, outcome.content());
                }
            }
            if (outcome.stopReading()) {
                break;
            }
        }

        if (!skippedByFileLimit.isEmpty()) {
            budget.recordExhaustion("maxGitFiles=" + maxFiles + " erreicht; "
                    + skippedByFileLimit.size() + " Datei(en) nicht gelesen");
            errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, sourceId,
                    "Nicht gelesen wegen maxGitFiles=" + maxFiles + ": " + describeList(skippedByFileLimit)
                            + ". Diese Dateien fehlen im Ergebnis.",
                    false, "maxGitFiles erhoehen oder die Dateiliste in der Konfiguration kuerzen."));
            notes.add("Wegen maxGitFiles nicht gelesen: " + describeList(skippedByFileLimit) + ".");
        }
        if (!skippedByTime.isEmpty()) {
            budget.recordExhaustion("Zeitbudget vor " + skippedByTime.size() + " Datei(en) erreicht");
            errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, sourceId,
                    "Nicht gelesen wegen abgelaufenem Erfassungsbudget: " + describeList(skippedByTime)
                            + ". Diese Dateien fehlen im Ergebnis.",
                    false, "collectTimeoutSeconds erhoehen oder spaeter erneut erfassen."));
            notes.add("Wegen Zeitbudget nicht gelesen: " + describeList(skippedByTime) + ".");
        }
        if (budget != null && budget.exhaustions().size() > exhaustionsBefore) {
            notes.addAll(budget.exhaustions().subList(exhaustionsBefore, budget.exhaustions().size()));
        }

        // 3) Inhalte aufloesen und bereinigte Zeilen uebernehmen.
        TemplateParameterResolver.Result parsed = resolver.resolve(templateContents, parameterContents,
                configuration.allowedParameterNames());
        notes.addAll(parsed.notes());
        List<FileReadResult> finalFiles = applyParsed(files, parsed);
        Map<String, ContainerConfig> containers = parsed.containers();
        Map<String, ConfigValue> rollout = parsed.rolloutTimeParameters();

        for (FileReadResult file : finalFiles) {
            if (erroredPaths.contains(file.relativePath())) {
                continue;
            }
            switch (file.status()) {
                case PARSE_ERROR -> errors.add(new OpsError(ErrorCode.SOURCE_PARSE_ERROR, sourceId,
                        "Die Datei konnte nicht ausgewertet werden: " + PathSafety.describe(file.relativePath()),
                        false, "Dateiinhalt und YAML-Struktur pruefen."));
                case AMBIGUOUS -> errors.add(new OpsError(ErrorCode.TARGET_AMBIGUOUS, sourceId,
                        "Mehrere Workload-Dokumente in " + PathSafety.describe(file.relativePath())
                                + "; die Zuordnung ist mehrdeutig.",
                        false, "Je Datei genau ein Deployment/DeploymentConfig-Dokument konfigurieren."));
                case UNSUPPORTED_TYPE -> errors.add(new OpsError(ErrorCode.UNSUPPORTED_WORKLOAD, sourceId,
                        "Kein Workload-Dokument (kind Deployment/DeploymentConfig) in "
                                + PathSafety.describe(file.relativePath()) + ".",
                        false, "Templatedatei oder Workload-Art in der Konfiguration pruefen."));
                default -> {
                }
            }
        }

        boolean gap = finalFiles.stream().anyMatch(file -> file.status().isGap());
        ResolvedConfiguration.ConfigStatus status = gap
                ? ResolvedConfiguration.ConfigStatus.PARTIAL
                : ResolvedConfiguration.ConfigStatus.RESOLVED;
        if (containers.isEmpty()) {
            notes.add("In den erlaubten Templatedateien wurde kein Workload-Container gefunden.");
        }
        ResolvedConfiguration data = new ResolvedConfiguration(repository.repositoryId(), localPath, revision,
                parsed.containerNames(), containers, finalFiles, status, rollout, List.of());
        SourceStatus sourceStatus = status == ResolvedConfiguration.ConfigStatus.RESOLVED
                ? SourceStatus.COLLECTED
                : SourceStatus.TRUNCATED;
        return new SourceResult<>(sourceId, sourceStatus, data, now, notes, errors);
    }

    /** Ergebnis einer einzelnen Datei inklusive Abbruchsignal bei technischem Fehler. */
    private record ReadOutcome(FileReadResult result, String content, boolean stopReading) {
    }

    private ReadOutcome readFile(GitObjectReader objects, String commit, String path,
            FileReadResult.FileRole role, CollectionBudget budget, String sourceId, List<OpsError> errors,
            List<String> notes, Set<String> erroredPaths) {
        try {
            if (objects.isSymbolicLinkAtCommit(commit, path)) {
                errors.add(PathSafety.scopeDenied(sourceId,
                        "Datei ist am Commit ein Symlink (Modus 120000) und wird nicht gelesen: "
                                + PathSafety.describe(path)));
                erroredPaths.add(path);
                return new ReadOutcome(new FileReadResult(path, role, FileReadResult.FileStatus.NOT_ALLOWED,
                        List.of(), null, null, commit,
                        "Symlink am Commit (Modus 120000); es wurde nicht gelesen."), null, false);
            }
            if (!objects.pathExistsAtCommit(commit, path)) {
                errors.add(new OpsError(ErrorCode.NOT_FOUND, sourceId,
                        "Die konfigurierte Datei ist am belegten Commit nicht vorhanden: "
                                + PathSafety.describe(path), false,
                        "Dateiliste in der Konfiguration und den Commit pruefen."));
                erroredPaths.add(path);
                return new ReadOutcome(new FileReadResult(path, role, FileReadResult.FileStatus.NOT_PRESENT,
                        List.of(), null, null, commit, "Am Commit nicht vorhanden."), null, false);
            }
            String content = objects.readFileAtCommit(commit, path);
            if (budget != null) {
                budget.recordInputBytes(content.length());
            }
            return new ReadOutcome(new FileReadResult(path, role, FileReadResult.FileStatus.READ,
                    TemplateParameterResolver.splitLines(content),
                    Long.valueOf(content.getBytes(StandardCharsets.UTF_8).length),
                    Hashes.sha256Hex(content), commit, ""), content, false);
        } catch (GitReadException ex) {
            errors.add(ex.toError(sourceId));
            erroredPaths.add(path);
            FileReadResult.FileStatus status = ex.code() == ErrorCode.NOT_FOUND
                    ? FileReadResult.FileStatus.NOT_PRESENT
                    : FileReadResult.FileStatus.PARSE_ERROR;
            boolean stop = ex.code() == ErrorCode.REQUEST_TIMEOUT || ex.code() == ErrorCode.CONFIG_INVALID;
            if (stop) {
                notes.add("Der Git-Lesezugriff wurde nach einem technischen Fehler abgebrochen.");
            }
            return new ReadOutcome(new FileReadResult(path, role, status, List.of(), null, null, commit,
                    ex.getMessage()), null, stop);
        }
    }

    /**
     * Uebernimmt die bereinigten Zeilen und den Auswertungsstatus aus dem Parser. Der Hash
     * bezieht sich damit auf den ausgelieferten, bereinigten Inhalt.
     */
    private List<FileReadResult> applyParsed(List<FileReadResult> files, TemplateParameterResolver.Result parsed) {
        List<FileReadResult> result = new ArrayList<>(files.size());
        for (FileReadResult file : files) {
            if (!parsed.knows(file.relativePath())) {
                // Die Datei wurde nicht aufgeloest (z. B. nicht erlaubt oder am Commit fehlend);
                // ihr Status bleibt unveraendert.
                result.add(file);
                continue;
            }
            List<String> lines = parsed.deliverableLines().get(file.relativePath());
            FileReadResult.FileStatus status = parsed.statusOf(file.relativePath());
            if (lines == null && status == file.status()) {
                result.add(file);
                continue;
            }
            List<String> effectiveLines = lines == null ? file.lines() : lines;
            String note = String.join(" ", parsed.notesOf(file.relativePath()));
            if (file.note() != null && !file.note().isBlank()) {
                note = note.isBlank() ? file.note() : file.note() + " " + note;
            }
            String content = effectiveLines.isEmpty() ? "" : String.join("\n", effectiveLines);
            Long byteSize = content.isEmpty()
                    ? file.byteSize()
                    : Long.valueOf(content.getBytes(StandardCharsets.UTF_8).length);
            String sha256 = content.isEmpty() ? file.sha256() : Hashes.sha256Hex(content);
            result.add(new FileReadResult(file.relativePath(), file.role(), status, effectiveLines,
                    byteSize, sha256, file.commit(), note));
        }
        return result;
    }

    private SourceResult<ResolvedConfiguration> revisionGap(String sourceId, RepositoryDefinition repository,
            RevisionProvenance revision, Instant now, List<String> notes, List<OpsError> errors) {
        ErrorCode code = revision == null || revision.source() == RevisionProvenance.Source.UNKNOWN
                ? ErrorCode.REVISION_UNKNOWN
                : ErrorCode.REVISION_UNVERIFIED;
        String message = revision == null || revision.source() == RevisionProvenance.Source.UNKNOWN
                ? "Es ist keine Konfigurationsrevision belegt; es wird nichts gelesen."
                : "Die Konfigurationsrevision ist nicht als vollstaendiger Commit belegt; "
                        + "es wird nichts gelesen.";
        errors.add(new OpsError(code, sourceId, message, false,
                "Strukturierte Buildmetadaten auswerten oder einen Commit ausdruecklich als Betreiberangabe setzen."));
        notes.add("Kein Rueckfall auf HEAD oder main: ohne belegten Commit wird keine Datei gelesen.");
        return unavailable(sourceId, repository.repositoryId(), revision, List.of(), now, notes, errors,
                SourceStatus.NOT_PRESENT);
    }

    private SourceResult<ResolvedConfiguration> unavailable(String sourceId, String repositoryId,
            RevisionProvenance revision, List<FileReadResult> files, Instant now, List<String> notes,
            List<OpsError> errors, SourceStatus status) {
        RevisionProvenance effective = revision == null
                ? RevisionProvenance.unknown(repositoryId == null ? sourceId : repositoryId,
                        "Keine Revision uebergeben.")
                : revision;
        ResolvedConfiguration data = ResolvedConfiguration.unavailable(
                repositoryId == null ? sourceId : repositoryId, null, effective, files);
        return new SourceResult<>(sourceId, status, data, now, notes, errors);
    }

    private GitObjectReader readerFor(Path root, CollectionBudget budget, Instant now) {
        if (budget == null) {
            return new GitObjectReader(new GitProcess(root, runner, STEP_TIMEOUT, MAX_OUTPUT_BYTES));
        }
        Duration timeout = budget.effectiveTimeout(STEP_TIMEOUT, now);
        if (timeout.isZero() || timeout.isNegative()) {
            return null;
        }
        return new GitObjectReader(new GitProcess(root, runner, timeout, MAX_OUTPUT_BYTES));
    }

    /**
     * Verbraucht das auftragsweite Aufrufbudget fuer die tatsaechlich geplanten Git-Aufrufe.
     * Der Adapter verbraucht es nicht jeweils vollstaendig, sondern nur um die Zahl der eigenen
     * Aufrufe.
     */
    private static boolean consumeRequests(CollectionBudget budget, int calls, String sourceId,
            List<OpsError> errors) {
        if (budget == null) {
            return true;
        }
        for (int i = 0; i < calls; i++) {
            if (!budget.tryConsumeRequest()) {
                errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, sourceId,
                        "Das Auftragsbudget fuer Quellaufrufe ist erschoepft; der Git-Lesezugriff wurde "
                                + "abgebrochen.",
                        false, "maxRequests oder collectTimeoutSeconds anpassen."));
                return false;
            }
        }
        return true;
    }

    private RepositoryDefinition firstRepository() {
        if (repositoryIdOverride != null) {
            return config.repositories().get(repositoryIdOverride);
        }
        return config.repositories().values().stream().findFirst().orElse(null);
    }

    private static FileReadResult.FileRole roleOf(String path, ConfigurationDefinition configuration) {
        if (configuration.templateFiles().contains(path)) {
            return FileReadResult.FileRole.TEMPLATE;
        }
        if (configuration.parameterFiles().contains(path)) {
            return FileReadResult.FileRole.PARAMETERS;
        }
        return FileReadResult.FileRole.OTHER;
    }

    private static boolean isRepository(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        return Files.exists(path.resolve(".git")) || Files.exists(path.resolve("HEAD"));
    }

    /** Symlink-Flucht im Arbeitsbaum; das Lesen erfolgt zwar aus Git-Objekten, geprueft wird trotzdem. */
    private static boolean worktreeEscapes(Path root, String relativePath) {
        try {
            PathSafety.resolveWithinNoSymlinkEscape(root, relativePath);
            return false;
        } catch (ScopeViolationException ex) {
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String describeList(List<String> values) {
        List<String> described = values.stream().map(PathSafety::describe).toList();
        String joined = String.join(", ", described);
        return joined.length() > 300 ? joined.substring(0, 300) + "..." : joined;
    }

    private static String shortOf(String commit) {
        return commit != null && commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    /** Nur fuer strukturierte Ausgaben; der Mapper wird fuer Projektionen des Adapters bereitgehalten. */
    public JsonMapper mapper() {
        return mapper;
    }
}
