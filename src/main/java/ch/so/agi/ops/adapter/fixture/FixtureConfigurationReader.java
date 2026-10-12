package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.adapter.git.TemplateParameterResolver;
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
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.util.DefaultProcessRunner;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.Hashes;
import ch.so.agi.ops.util.ProcessRunner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtureadapter fuer die Konfigurationsquelle.
 *
 * <p>Fuer jede erlaubte Datei gilt: existiert im Szenario eine Revisionskopie
 * {@code repo-files/<commit>/<pfad>}, wird diese verwendet. Sonst wird die Datei ueber das
 * echte Git aus dem Szenario-Repository am uebergebenen Commit gelesen
 * ({@code git -C <scenario>/repo show <commit>:<pfad>}), ausschliesslich lesend und ohne
 * Hooks oder externe Diff-Programme. Fehlt der Commit, wird {@code REVISION_UNAVAILABLE}
 * gemeldet und ausdruecklich <em>kein</em> anderer Commit als Ersatz verwendet.</p>
 *
 * <p>Die Aufloesung der Template-/Parameterwerte erfolgt durch
 * {@link ch.so.agi.ops.adapter.git.TemplateParameterResolver}, also durch dieselbe Logik wie im
 * Livebetrieb. Dieser Adapter liest nur Dateien und ordnet Rollen zu.</p>
 */
final class FixtureConfigurationReader implements ConfigurationReader {

    private static final String SOURCE_ID = "fixture-configuration";
    private static final String REFERENCE_NOTE = "Zusaetzlich gelesene Datei des heutigen Stands; "
            + "ausdruecklich kein historischer Sollzustand und kein Ersatz fuer die belegte Revision.";

    private final OpsConfig config;
    private final FixtureScenarios fixtures;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ProcessRunner processRunner;
    private final TemplateParameterResolver resolver;

    FixtureConfigurationReader(OpsConfig config, FixtureScenarios fixtures, JsonMapper mapper, Clock clock) {
        this(config, fixtures, mapper, clock, DefaultProcessRunner.inheritingEnvironment());
    }

    /** Testbare Variante mit injiziertem Prozesslauf. */
    FixtureConfigurationReader(OpsConfig config, FixtureScenarios fixtures, JsonMapper mapper, Clock clock,
            ProcessRunner processRunner) {
        this.config = config;
        this.fixtures = fixtures;
        this.mapper = mapper;
        this.clock = clock;
        this.processRunner = processRunner;
        this.resolver = new TemplateParameterResolver();
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public SourceResult<ResolvedConfiguration> collect(ResolvedTarget target, RevisionProvenance revision,
            CollectionBudget budget) {
        Instant collectedAt = clock.instant();
        FixtureData scenario;
        try {
            scenario = fixtures.forTarget(target);
        } catch (RuntimeException ex) {
            return SourceResult.of(SOURCE_ID, SourceStatus.UNREACHABLE,
                    ResolvedConfiguration.unavailable(repositoryId(target, revision), null,
                            unknownRevision(target, revision), List.of()),
                    collectedAt, List.of(), List.of(OpsError.of(ErrorCode.CONFIG_INCOMPLETE, SOURCE_ID,
                            "Das Fixture-Szenario ist nicht aufloesbar.")));
        }

        ConfigurationDefinition definition = target == null || target.definition() == null
                ? null
                : target.definition().configuration();
        String repositoryId = repositoryId(target, revision);
        String localPath = localPathDisplay(target, scenario);

        if (revision == null || !revision.hasCommit()) {
            return SourceResult.of(SOURCE_ID, SourceStatus.NOT_PRESENT,
                    ResolvedConfiguration.unavailable(repositoryId, localPath,
                            revision == null ? RevisionProvenance.unknown(repositoryId,
                                    "Keine Revisionsangabe im Auftrag.") : revision, List.of()),
                    collectedAt,
                    List.of("Ohne belegten Commit wird keine Konfigurationsdatei gelesen."),
                    List.of(new OpsError(ErrorCode.REVISION_UNKNOWN, SOURCE_ID,
                            "Die Konfigurationsrevision ist nicht bekannt; es wurde keine Datei gelesen "
                                    + "und kein Ersatzcommit verwendet.", false,
                            "Die Revisionszuordnung des Builds klaeren.")));
        }

        List<String> templateFiles = definition == null ? List.of() : definition.templateFiles();
        List<String> parameterFiles = definition == null ? List.of() : definition.parameterFiles();
        List<String> allowedParameters = definition == null ? List.of() : definition.allowedParameterNames();

        List<FileReadResult> fileResults = new ArrayList<>();
        List<OpsError> errors = new ArrayList<>();
        Map<String, String> templateTexts = new LinkedHashMap<>();
        Map<String, String> parameterTexts = new LinkedHashMap<>();
        boolean commitAvailable = false;
        int readCount = 0;
        int maxFiles = config.limits().maxGitFiles();

        for (String configuredPath : templateFiles) {
            ReadOutcome outcome = readFile(scenario, revision.commit(), configuredPath,
                    FileReadResult.FileRole.TEMPLATE, budget);
            if (outcome.result() != null) {
                fileResults.add(outcome.result());
            }
            if (outcome.error() != null) {
                errors.add(outcome.error());
            }
            if (outcome.text() != null) {
                commitAvailable = true;
                readCount++;
                templateTexts.put(fixtures.scenarioRelative(scenario, configuredPath), outcome.text());
            }
            if (readCount > maxFiles) {
                errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, SOURCE_ID,
                        "Das Dateibudget maxGitFiles=" + maxFiles + " wurde erreicht.", false,
                        "Die Liste erlaubter Konfigurationsdateien verkleinern."));
                break;
            }
        }
        for (String configuredPath : parameterFiles) {
            ReadOutcome outcome = readFile(scenario, revision.commit(), configuredPath,
                    FileReadResult.FileRole.PARAMETERS, budget);
            if (outcome.result() != null) {
                fileResults.add(outcome.result());
            }
            if (outcome.error() != null) {
                errors.add(outcome.error());
            }
            if (outcome.text() != null) {
                commitAvailable = true;
                parameterTexts.put(fixtures.scenarioRelative(scenario, configuredPath), outcome.text());
            }
        }

        if (!commitAvailable) {
            // Der belegte Commit ist nicht lesbar. Die zusaetzlich gelesenen Referenzdateien des
            // heutigen Stands werden trotzdem gefuehrt - ausdruecklich als Referenz, nicht als
            // Ersatz fuer den historischen Sollzustand.
            List<FileReadResult> withReferenceOnly = withReferences(scenario, revision, fileResults, errors, budget);
            var unavailableNotes = new ArrayList<String>();
            unavailableNotes.add("Der Commit " + revision.shortCommit() + " ist im Szenario nicht vorhanden; es "
                    + "wird kein anderer Commit als Ersatz verwendet.");
            List<String> references = fixtures.referenceFiles(scenario);
            if (!references.isEmpty()) {
                unavailableNotes.add("Zusaetzlich gelesene Referenzdateien des heutigen Stands: "
                        + String.join(", ", references) + ". Sie gelten ausdruecklich nicht als historischer "
                        + "Sollzustand.");
            }
            List<String> currentReferenceFiles = withReferenceOnly.stream()
                    .filter(file -> file.role() == FileReadResult.FileRole.REFERENCE_ONLY
                            && file.status() == FileReadResult.FileStatus.READ)
                    .map(FileReadResult::relativePath)
                    .toList();
            ResolvedConfiguration unavailableData = new ResolvedConfiguration(repositoryId, localPath, revision,
                    List.of(), Map.of(), withReferenceOnly, ResolvedConfiguration.ConfigStatus.UNAVAILABLE,
                    Map.of(), currentReferenceFiles);
            return SourceResult.of(SOURCE_ID, SourceStatus.NOT_PRESENT, unavailableData,
                    collectedAt, List.copyOf(unavailableNotes),
                    List.of(new OpsError(ErrorCode.REVISION_UNAVAILABLE, SOURCE_ID,
                            "Die Konfigurationsdateien sind am Commit " + revision.shortCommit()
                                    + " nicht lesbar. Es wurde kein Ersatzcommit (weder HEAD noch main) gelesen.",
                            false, "Den lokalen Checkout um den belegten Commit ergaenzen.")));
        }

        if (budget != null) {
            for (String text : templateTexts.values()) {
                budget.recordInputBytes(text.getBytes(StandardCharsets.UTF_8).length);
            }
        }

        ResolvedConfiguration resolved;
        try {
            resolved = invokeResolver(scenario, revision, repositoryId, localPath, templateTexts, parameterTexts,
                    allowedParameters, List.copyOf(fileResults), errors);
        } catch (RuntimeException ex) {
            return SourceResult.of(SOURCE_ID, SourceStatus.UNREACHABLE,
                    ResolvedConfiguration.unavailable(repositoryId, localPath, revision, List.copyOf(fileResults)),
                    collectedAt, List.of(),
                    List.of(new OpsError(ErrorCode.SOURCE_PARSE_ERROR, SOURCE_ID,
                            "Die Aufloesung der Template-/Parameterwerte ist fehlgeschlagen ("
                                    + ch.so.agi.ops.security.PathSafety.describe(ex.getClass().getSimpleName())
                                    + ").", false,
                            "Die synthetischen Template-/Parameterdateien des Szenarios pruefen.")));
        }

        List<FileReadResult> allFiles = withReferences(scenario, revision, resolved.files(), errors, budget);
        boolean gap = allFiles.stream().anyMatch(file -> file.status() != FileReadResult.FileStatus.READ)
                || !errors.isEmpty();
        ResolvedConfiguration withReferences = new ResolvedConfiguration(resolved.repositoryId(),
                resolved.localPath(), resolved.revision(), resolved.containerNames(), resolved.workloads(),
                allFiles, gap ? ResolvedConfiguration.ConfigStatus.PARTIAL : resolved.status(),
                resolved.rolloutTimeParameters(), resolved.currentReferenceFiles());
        SourceStatus status = gap ? SourceStatus.TRUNCATED : SourceStatus.COLLECTED;
        List<String> notes = new ArrayList<>();
        notes.add("Konfiguration aus dem synthetischen Szenario gelesen; der Arbeitsbaum des "
                + "Szenarios wurde nicht veraendert.");
        if (!resolved.currentReferenceFiles().isEmpty()) {
            notes.add("Zusaetzlich gelesene Referenzdateien des heutigen Stands: "
                    + String.join(", ", resolved.currentReferenceFiles())
                    + ". Sie gelten ausdruecklich nicht als historischer Sollzustand.");
        }
        return SourceResult.of(SOURCE_ID, status, withReferences, collectedAt, List.copyOf(notes),
                List.copyOf(errors));
    }

    // --- Dateien ---------------------------------------------------------------------------

    /**
     * Ergebnis des Lesens einer einzelnen Datei.
     *
     * @param result Ergebnisdatensatz oder {@code null}, wenn die Rolle nicht gefuehrt wird
     * @param text   gelesener Text oder {@code null}
     * @param error  erklaerter Fehler oder {@code null}
     */
    private record ReadOutcome(FileReadResult result, String text, OpsError error) {
    }

    private ReadOutcome readFile(FixtureData scenario, String commit, String configuredPath,
            FileReadResult.FileRole role, CollectionBudget budget) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return new ReadOutcome(null, null, null);
        }
        if (budget != null && !budget.tryConsumeRequest()) {
            return new ReadOutcome(null, null, new OpsError(ErrorCode.BUDGET_EXCEEDED, SOURCE_ID,
                    "Das Budget der Quellaufrufe wurde erreicht; die Datei wurde nicht gelesen.", false,
                    "Das Auftragsbudget pruefen."));
        }
        // Der konfigurierte Pfad ist die szenarioweite, portable Angabe (z. B.
        // t01-liveness-startup/repo/templates/modelfinder.yaml) und bleibt in den Ergebnissen
        // so erhalten. Fuer den Zugriff innerhalb des Szenarios wird der Szenarioanteil entfernt.
        String relative = configuredPath.startsWith("/") ? configuredPath.substring(1) : configuredPath;
        // Reihenfolge: vorbereitete Revisionskopie, dann echter Git-Checkout am belegten Commit.
        // Beide Quellen gehoeren zu demselben Szenario; ein anderer Commit wird nie verwendet.
        String text = readFromRepoFiles(scenario, commit, relative);
        boolean fromGit = false;
        if (text == null) {
            text = readFromGit(scenario, commit, relative);
            fromGit = text != null;
        }
        if (text == null) {
            // Der im Szenario mitgelieferte Revisionsstand des Arbeitsbaums. Er ist ebenfalls
            // synthetisch und gehoert zu genau diesem Commit.
            text = readFromRepoWorkspace(scenario, relative);
        }
        if (text == null) {
            boolean gitStrategy = FixtureScenarios.STRATEGY_GIT.equals(fixtures.repoStrategy(scenario));
            String note = gitStrategy
                    ? "Weder eine Revisionskopie noch ein Git-Arbeitsbaum liefert diese Datei am Commit "
                            + shortCommit(commit) + "."
                    : "Keine Revisionskopie und kein Git-Arbeitsbaum mit diesem Commit vorhanden.";
            return new ReadOutcome(new FileReadResult(relative, role, FileReadResult.FileStatus.NOT_PRESENT,
                    List.of(), null, null, commit, note),
                    null, new OpsError(ErrorCode.REVISION_UNAVAILABLE, SOURCE_ID,
                            "Die Datei " + relative + " ist am Commit " + shortCommit(commit)
                                    + " nicht lesbar. Es wurde kein anderer Commit als Ersatz gelesen.",
                            false, "Die synthetische Revisionskopie oder den Git-Arbeitsbaum ergaenzen."));
        }
        List<String> lines = splitLines(text);
        String note = fromGit
                ? "Gelesen aus dem Git-Arbeitsbaum des Szenarios am belegten Commit; der Arbeitsbaum "
                        + "wurde nicht veraendert."
                : "Gelesen aus der vorbereiteten Revisionskopie des Szenarios.";
        return new ReadOutcome(new FileReadResult(relative, role, FileReadResult.FileStatus.READ, lines,
                (long) text.length(), Hashes.sha256Hex(text), commit, note), text, null);
    }

    /**
     * Liest eine Datei aus dem im Szenario mitgelieferten Revisionsstand des Arbeitsbaums.
     *
     * <p>Der Revisionsstand wird ueber {@code repo/repo-setup.json} bestimmt; der Name des
     * Verzeichnisses ist damit Szenariodaten und nicht im Code verdrahtet.</p>
     */
    private String readFromRepoWorkspace(FixtureData scenario, String relativePath) {
        String setupPath = FixtureScenarios.DEFAULT_REPO_PATH + "/repo-setup.json";
        if (!fixtures.exists(scenario, setupPath)) {
            return null;
        }
        tools.jackson.databind.JsonNode setup = fixtures.readRawJson(scenario, setupPath);
        tools.jackson.databind.JsonNode commits = setup == null ? null : setup.get("commits");
        if (commits == null || !commits.isArray()) {
            return null;
        }
        for (tools.jackson.databind.JsonNode commit : commits) {
            tools.jackson.databind.JsonNode id = commit.get("id");
            if (id == null || !id.isString()) {
                continue;
            }
            String text = fixtures.readRaw(scenario, FixtureScenarios.DEFAULT_REPO_PATH + "/commits/"
                    + id.asString() + "/" + relativePath);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String readFromRepoFiles(FixtureData scenario, String commit, String relativePath) {
        if (commit == null || commit.isBlank() || relativePath.isBlank()) {
            return null;
        }
        return fixtures.readRaw(scenario, FixtureScenarios.REPO_FILES_DIR + "/" + commit + "/" + relativePath);
    }

    /** Liest eine Datei lesend aus dem Szenario-Repository: {@code git show <commit>:<pfad>}. */
    private String readFromGit(FixtureData scenario, String commit, String relativePath) {
        if (commit == null || commit.isBlank() || relativePath.isBlank()) {
            return null;
        }
        Path repo = fixtures.repoPath(scenario);
        if (!FixtureScenarios.isGitCheckout(repo)) {
            return null;
        }
        if (!isSafeGitPath(relativePath)) {
            return null;
        }
        ExecResult result = processRunner.exec("git", List.of("-C", repo.toString(), "show",
                commit + ":" + relativePath), null, Map.of("GIT_TERMINAL_PROMPT", "0",
                        "GIT_OPTIONAL_LOCKS", "0", "GIT_CONFIG_NOSYSTEM", "1"),
                config.limits().requestTimeout(), MAX_GIT_FILE_BYTES);
        if (result == null || !result.success()) {
            return null;
        }
        return result.stdout();
    }

    /** Obergrenze fuer eine einzelne aus Git gelesene Konfigurationsdatei. */
    private static final long MAX_GIT_FILE_BYTES = 1024L * 1024;

    private static boolean isSafeGitPath(String relativePath) {
        if (relativePath.contains("..") || relativePath.contains(":") || relativePath.startsWith("/")
                || relativePath.contains("\\") || relativePath.contains("\u0000")) {
            return false;
        }
        return !relativePath.isBlank();
    }

    // --- Aufloesung ------------------------------------------------------------------------

    private ResolvedConfiguration invokeResolver(FixtureData scenario, RevisionProvenance revision,
            String repositoryId, String localPath, Map<String, String> templateTexts,
            Map<String, String> parameterTexts, List<String> allowedParameters, List<FileReadResult> files,
            List<OpsError> errors) {
        if (templateTexts.isEmpty() && parameterTexts.isEmpty()) {
            return new ResolvedConfiguration(repositoryId, localPath, revision, List.of(), Map.of(),
                    List.copyOf(files), ResolvedConfiguration.ConfigStatus.PARTIAL, Map.of(), List.of());
        }
        TemplateParameterResolver.Result parsed;
        try {
            parsed = resolver.resolve(templateTexts, parameterTexts, allowedParameters);
        } catch (RuntimeException ex) {
            errors.add(new OpsError(ErrorCode.SOURCE_PARSE_ERROR, SOURCE_ID,
                    "Die Aufloesung der Template-/Parameterwerte ist fehlgeschlagen ("
                            + ch.so.agi.ops.security.PathSafety.describe(ex.getClass().getSimpleName()) + ").",
                    false, "Die synthetischen Template-/Parameterdateien des Szenarios pruefen."));
            return new ResolvedConfiguration(repositoryId, localPath, revision, List.of(), Map.of(),
                    List.copyOf(files), ResolvedConfiguration.ConfigStatus.PARTIAL, Map.of(), List.of());
        }
        List<FileReadResult> resolvedFiles = applyParsed(files, parsed);
        ResolvedConfiguration.ConfigStatus status = resolvedFiles.stream()
                .filter(file -> file.role() != FileReadResult.FileRole.REFERENCE_ONLY)
                .anyMatch(file -> file.status() != FileReadResult.FileStatus.READ)
                        ? ResolvedConfiguration.ConfigStatus.PARTIAL
                        : ResolvedConfiguration.ConfigStatus.RESOLVED;
        return new ResolvedConfiguration(repositoryId, localPath, revision, parsed.containerNames(),
                parsed.containers(), resolvedFiles, status, parsed.rolloutTimeParameters(), List.of());
    }

    /** Uebernimmt Dateistatus und ausgelieferte Zeilen aus der gemeinsamen Aufloesung. */
    private static List<FileReadResult> applyParsed(List<FileReadResult> files,
            TemplateParameterResolver.Result parsed) {
        List<FileReadResult> result = new ArrayList<>(files.size());
        for (FileReadResult file : files) {
            // Ohne knows()-Pruefung liefert statusOf(...) fuer unbekannte Dateien READ als
            // Default und wuerde einen bereits ermittelten Status (NOT_ALLOWED/NOT_PRESENT/
            // REFERENCE_ONLY) faelschlich ueberschreiben.
            boolean known = parsed.knows(file.relativePath());
            FileReadResult.FileStatus status = known ? parsed.statusOf(file.relativePath()) : file.status();
            List<String> lines = known
                    ? parsed.deliverableLines().getOrDefault(file.relativePath(), file.lines())
                    : file.lines();
            List<String> notes = parsed.notesOf(file.relativePath());
            result.add(new FileReadResult(file.relativePath(), file.role(), status, lines, file.byteSize(),
                    file.sha256(), file.commit(), notes.isEmpty() ? file.note() : String.join(" ", notes)));
        }
        return List.copyOf(result);
    }

    // --- Referenzdateien des heutigen Stands -----------------------------------------------

    private List<FileReadResult> withReferences(FixtureData scenario, RevisionProvenance revision,
            List<FileReadResult> files, List<OpsError> errors, CollectionBudget budget) {
        List<String> references = fixtures.referenceFiles(scenario);
        if (references.isEmpty()) {
            return files;
        }
        List<FileReadResult> result = new ArrayList<>(files);
        for (String configuredPath : references) {
            String relative = fixtures.scenarioRelative(scenario, configuredPath);
            if (budget != null && !budget.tryConsumeRequest()) {
                errors.add(new OpsError(ErrorCode.BUDGET_EXCEEDED, SOURCE_ID,
                        "Das Budget der Quellaufrufe wurde erreicht; die Referenzdatei wurde nicht gelesen.",
                        false, "Das Auftragsbudget pruefen."));
                break;
            }
            // Kanonische Ablageform: <scenario>/<konfigurierter Pfad>, also einschliesslich
            // des Szenarioanteils, wie er auch in der Konfiguration steht.
            String text = fixtures.readRaw(scenario, FixtureScenarios.REFERENCE_DIR + "/" + configuredPath);
            if (text == null) {
                text = fixtures.readRaw(scenario, FixtureScenarios.REFERENCE_DIR + "/" + relative);
            }
            if (text == null) {
                text = fixtures.readRaw(scenario, FixtureScenarios.REPO_FILES_DIR + "/current/" + relative);
            }
            if (text == null) {
                // Der heutige Stand wird nur ueber den Standardzweig gelesen und ausdruecklich als
                // Referenz gefuehrt, nie als historischer Sollzustand.
                text = readFromGit(scenario, "HEAD", relative);
            }
            if (text == null) {
                result.add(new FileReadResult(relative, FileReadResult.FileRole.REFERENCE_ONLY,
                        FileReadResult.FileStatus.NOT_PRESENT, List.of(), null, null, null,
                        "Referenzdatei des heutigen Stands ist im Szenario nicht vorhanden."));
                continue;
            }
            if (budget != null) {
                budget.recordInputBytes(text.getBytes(StandardCharsets.UTF_8).length);
            }
            result.add(new FileReadResult(relative, FileReadResult.FileRole.REFERENCE_ONLY,
                    FileReadResult.FileStatus.READ, splitLines(text), (long) text.length(), Hashes.sha256Hex(text),
                    null, REFERENCE_NOTE));
        }
        return List.copyOf(result);
    }

    // --- Hilfsmittel ------------------------------------------------------------------------

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(Redactor.redact(line).text());
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return List.copyOf(lines);
    }

    private static String shortCommit(String commit) {
        if (commit == null) {
            return "unbekannt";
        }
        return commit.length() >= 8 ? commit.substring(0, 8) : commit;
    }

    private String repositoryId(ResolvedTarget target, RevisionProvenance revision) {
        if (revision != null && revision.repositoryId() != null && !revision.repositoryId().isBlank()) {
            return revision.repositoryId();
        }
        if (target != null && target.definition() != null && target.definition().configuration() != null
                && target.definition().configuration().repository() != null) {
            return target.definition().configuration().repository();
        }
        return SOURCE_ID;
    }

    private RevisionProvenance unknownRevision(ResolvedTarget target, RevisionProvenance revision) {
        return revision == null
                ? RevisionProvenance.unknown(repositoryId(target, null), "Keine Revisionsangabe im Auftrag.")
                : revision;
    }

    private String localPathDisplay(ResolvedTarget target, FixtureData scenario) {
        RepositoryDefinition repository = target == null || target.definition() == null
                || target.definition().configuration() == null
                ? null
                : config.repository(target.definition().configuration().repository());
        if (repository != null && repository.localPath() != null) {
            return repository.localPath().toString();
        }
        return scenario.directory().toString();
    }
}
