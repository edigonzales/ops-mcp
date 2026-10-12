package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.PathSafety;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Aufloesung synthetischer Szenarien fuer den Fixturebetrieb.
 *
 * <p>Ein Szenario ist ein Verzeichnis unter {@code fixture.root}. Es enthaelt Rohantworten in
 * den Formen, die die echten Quellen liefern, und ausdruecklich nicht die gewuenschte
 * Enddiagnose. Diese Klasse loest Szenariokennungen pfadsicher auf, liest {@code scenario.json}
 * und stellt die optionalen Fixtureeinstellungen bereit, die das Konfigurationsschema nicht
 * abbildet:</p>
 *
 * <ul>
 *   <li>{@code repoStrategy}: {@code git} (Standard) oder {@code repo-files},</li>
 *   <li>{@code repoPath}: Verzeichnis des Git-Checkouts relativ zum Szenario,</li>
 *   <li>{@code referenceFiles}: Dateien, die zusaetzlich als {@code REFERENCE_ONLY} gelesen
 *       werden (heutiger Stand, ausdruecklich kein historischer Sollzustand).</li>
 * </ul>
 *
 * <p>Alle Pfade bleiben innerhalb des Szenarioverzeichnisses. Es findet kein Netzwerkzugriff
 * statt.</p>
 */
final class FixtureScenarios {

    /** Standardmaessiger Arbeitsbaum eines Szenarios. */
    static final String DEFAULT_REPO_PATH = "repo";

    /** Verzeichnis der vorbereiteten Dateikopien je Commit ({@code <commit>/<pfad>}). */
    static final String REPO_FILES_DIR = "repo-files";

    /** Verzeichnis der zusaetzlich gelesenen Referenzdateien des heutigen Stands. */
    static final String REFERENCE_DIR = "repo-current";

    static final String STRATEGY_GIT = "git";
    static final String STRATEGY_REPO_FILES = "repo-files";

    /** Obergrenze fuer gelesene Szenariodateien (Rohantworten sind klein und synthetisch). */
    private static final long MAX_FIXTURE_FILE_BYTES = 4L * 1024 * 1024;

    private final Path root;
    private final String defaultScenarioId;
    private final JsonMapper mapper;

    FixtureScenarios(Path root, String defaultScenarioId, JsonMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.defaultScenarioId = defaultScenarioId;
        this.mapper = mapper;
    }

    static FixtureScenarios of(OpsConfig config, JsonMapper mapper) {
        Path root = config.fixture() == null ? null : config.fixture().root();
        if (root == null) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Im Fixturemodus fehlt fixture.root in der Konfiguration.");
        }
        String scenario = config.fixture().scenario();
        if (scenario != null && !scenario.isBlank() && !FixtureData.isValidScenarioId(scenario)) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INVALID,
                    "Ungueltige Szenariokennung in fixture.scenario.");
        }
        return new FixtureScenarios(root, scenario, mapper);
    }

    Path root() {
        return root;
    }

    String defaultScenarioId() {
        return defaultScenarioId;
    }

    /**
     * Szenario eines Auftrags. Die explizite Szenariokennung des Ziels hat Vorrang vor dem
     * Standard der Konfiguration; fehlt beides, ist der Fixturebetrieb nicht eindeutig.
     */
    FixtureData forTarget(ch.so.agi.ops.application.port.ResolvedTarget target) {
        String scenarioId = target == null ? null : target.scenarioId();
        if (scenarioId == null || scenarioId.isBlank()) {
            return requireDefault();
        }
        return forId(scenarioId);
    }

    FixtureData requireDefault() {
        if (defaultScenarioId == null || defaultScenarioId.isBlank()) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Im Fixturemodus ist kein Szenario bestimmt: fixture.scenario fehlt und der Auftrag "
                            + "nennt keine Szenariokennung.");
        }
        return forId(defaultScenarioId);
    }

    FixtureData forId(String scenarioId) {
        return new FixtureData(directory(scenarioId), mapper);
    }

    private Path directory(String scenarioId) {
        if (!FixtureData.isValidScenarioId(scenarioId)) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INVALID,
                    "Ungueltige Szenariokennung: " + PathSafety.describe(scenarioId));
        }
        return PathSafety.resolveWithin(root, scenarioId);
    }

    /** Alle Szenariokennungen unter der Wurzel, alphabetisch sortiert. */
    List<String> knownScenarios() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(FixtureData::isValidScenarioId)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    // --- Rohdateien ------------------------------------------------------------------------

    /** Rohantwort als Text; {@code null}, wenn die Datei fehlt oder unzulaessig ist. */
    String readRaw(FixtureData scenario, String relativePath) {
        Path path = existing(scenario, relativePath);
        if (path == null) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ArtifactIntegrityException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Fixture-Datei ist nicht lesbar: " + relativePath);
        }
    }

    /** Rohantwort als JSON; {@code null}, wenn die Datei fehlt. */
    JsonNode readRawJson(FixtureData scenario, String relativePath) {
        String text = readRaw(scenario, relativePath);
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (RuntimeException ex) {
            throw new ArtifactIntegrityException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Fixture-Datei ist kein gueltiges JSON: " + relativePath);
        }
    }

    boolean exists(FixtureData scenario, String relativePath) {
        return existing(scenario, relativePath) != null;
    }

    long size(FixtureData scenario, String relativePath) {
        Path path = existing(scenario, relativePath);
        if (path == null) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private Path existing(FixtureData scenario, String relativePath) {
        Path path = candidate(scenario, relativePath);
        if (path == null || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            return null;
        }
        return path;
    }

    /**
     * Bringt einen konfigurierten Dateipfad auf die szenariorelative Form.
     *
     * <p>Die Fixturekonfiguration nennt Dateien einschliesslich des Szenarioanteils
     * (z. B. {@code t01-liveness-startup/repo/templates/modelfinder.yaml}), damit derselbe Pfad
     * auch fuer einen echten Checkout gilt. Fuer den Zugriff innerhalb des Szenarios wird der
     * fuehrende Szenarioanteil entfernt.</p>
     */
    String scenarioRelative(FixtureData scenario, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return "";
        }
        String cleaned = configuredPath.startsWith("/") ? configuredPath.substring(1) : configuredPath;
        if (cleaned.startsWith(scenario.id() + "/")) {
            cleaned = cleaned.substring(scenario.id().length() + 1);
        }
        return cleaned;
    }

    /** Datei relativ zum Szenario; {@code null}, wenn die Angabe unzulaessig ist. */
    Path candidate(FixtureData scenario, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path resolved = PathSafety.resolveWithin(scenario.directory(), relativePath);
            return Files.isRegularFile(resolved) && Files.size(resolved) > MAX_FIXTURE_FILE_BYTES
                    ? null
                    : resolved;
        } catch (IOException ex) {
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Prueft, ob ein Git-Arbeitsbaum vorliegt (ohne Git auszufuehren). */
    static boolean isGitCheckout(Path path) {
        return path != null && Files.isDirectory(path) && Files.exists(path.resolve(".git"));
    }

    // --- Optionale Szenarioeinstellungen ---------------------------------------------------

    private JsonNode descriptor(FixtureData scenario) {
        try {
            return readRawJson(scenario, "scenario.json");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Aufloesungsstrategie fuer Konfigurationsdateien; Vorgabe ist {@code git}. */
    String repoStrategy(FixtureData scenario) {
        JsonNode value = field(descriptor(scenario), "repoStrategy");
        if (value != null && value.isString()) {
            String strategy = value.asString();
            if (STRATEGY_REPO_FILES.equals(strategy) || STRATEGY_GIT.equals(strategy)) {
                return strategy;
            }
        }
        return STRATEGY_GIT;
    }

    /** Arbeitsbaum des Szenarios; relativ zum Szenario, pfadsicher. */
    Path repoPath(FixtureData scenario) {
        JsonNode value = field(descriptor(scenario), "repoPath");
        String relative = value != null && value.isString() ? value.asString() : DEFAULT_REPO_PATH;
        return scenario.resolve(relative);
    }

    /** Verzeichnis der Dateikopien je Commit. */
    Path repoFilesDir(FixtureData scenario) {
        return scenario.resolve(REPO_FILES_DIR);
    }

    /** Verzeichnis der Referenzdateien des heutigen Stands. */
    Path referenceDir(FixtureData scenario) {
        return scenario.resolve(REFERENCE_DIR);
    }

    /** Dateien, die zusaetzlich als {@code REFERENCE_ONLY} gelesen werden. */
    List<String> referenceFiles(FixtureData scenario) {
        JsonNode value = field(descriptor(scenario), "referenceFiles");
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(node -> {
                if (node.isString() && !node.asString().isBlank()) {
                    result.add(node.asString());
                }
            });
        }
        return List.copyOf(result);
    }

    private static JsonNode field(JsonNode node, String name) {
        return node == null ? null : node.get(name);
    }
}
