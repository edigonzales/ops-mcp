package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import ch.so.agi.ops.util.Hashes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Zugriff auf ein synthetisches Szenarioverzeichnis.
 *
 * <p>Ein Szenario enthaelt <em>Rohantworten</em> in den Formen, die die echten Quellen
 * liefern (Jenkins-JSON, Kubernetes-Listen, Kubernetes-Events, Console-Logs,
 * {@code .env}-aehnliche Parameterdateien, ein temporaeres Git-Repository). Es enthaelt
 * ausdruecklich <em>nicht</em> die gewuenschte Enddiagnose und keine echten Betriebsdaten.</p>
 *
 * <p>Alle Pfade werden gegen das Szenarioverzeichnis begrenzt; {@code ..}, absolute Pfade und
 * Symlink-Fluchten sind unzulaessig.</p>
 */
public final class FixtureData {

    private final Path scenarioDir;
    private final JsonMapper mapper;
    private final JsonNode descriptor;

    public FixtureData(Path scenarioDir, JsonMapper mapper) {
        this.scenarioDir = scenarioDir.toAbsolutePath().normalize();
        this.mapper = mapper;
        if (!Files.isDirectory(this.scenarioDir)) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Fixture-Szenario nicht gefunden: " + this.scenarioDir.getFileName());
        }
        this.descriptor = readJson("scenario.json");
    }

    public Path directory() {
        return scenarioDir;
    }

    public String id() {
        return text(descriptor, "id", scenarioDir.getFileName().toString());
    }

    public String title() {
        return text(descriptor, "title", id());
    }

    public String expectation() {
        return text(descriptor, "expectation", "");
    }

    /** Kennzeichnet das Szenario ausdruecklich als synthetisch. */
    public boolean synthetic() {
        JsonNode value = descriptor.get("synthetic");
        return value == null || !value.isBoolean() || value.asBoolean();
    }

    public List<String> tags() {
        List<String> result = new ArrayList<>();
        JsonNode value = descriptor.get("tags");
        if (value != null && value.isArray()) {
            value.forEach(node -> {
                if (node.isString()) {
                    result.add(node.asString());
                }
            });
        }
        return List.copyOf(result);
    }

    /** Liest eine JSON-Rohantwort aus dem Szenarioverzeichnis. */
    public JsonNode readJson(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new ArtifactIntegrityException(ErrorCode.NOT_FOUND,
                    "Fixture-Datei fehlt: " + relativePath);
        }
        try {
            return mapper.readTree(Files.readAllBytes(file));
        } catch (IOException | RuntimeException ex) {
            throw new ArtifactIntegrityException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Fixture-Datei ist kein gueltiges JSON: " + relativePath);
        }
    }

    /** Liest eine Rohantwort als Text (Console-Log, Logdatei), falls vorhanden. */
    public String readTextIfPresent(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ArtifactIntegrityException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Fixture-Datei ist nicht lesbar: " + relativePath);
        }
    }

    /** Liest eine Textdatei und wirft, wenn sie fehlt. */
    public String readText(String relativePath) {
        String text = readTextIfPresent(relativePath);
        if (text == null) {
            throw new ArtifactIntegrityException(ErrorCode.NOT_FOUND, "Fixture-Datei fehlt: " + relativePath);
        }
        return text;
    }

    public boolean has(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    /** Hash einer Fixture-Datei; dient der Herkunftsangabe im Snapshot. */
    public String hash(String relativePath) {
        try {
            return Hashes.sha256Hex(resolve(relativePath));
        } catch (IOException ex) {
            return "";
        }
    }

    /** Listet die Dateien eines Unterverzeichnisses, alphabetisch sortiert. */
    public List<String> listFiles(String relativeDir) {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /** Pfad eines Unterverzeichnisses innerhalb des Szenarios (z. B. das Git-Repository). */
    public Path resolve(String relativePath) {
        return ch.so.agi.ops.security.PathSafety.resolveWithin(scenarioDir, relativePath);
    }

    /** Zeitstempel aus scenario.json, soweit vorhanden. */
    public java.time.Instant instant(String field) {
        String value = text(descriptor, field, null);
        if (value == null) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INVALID,
                    "Ungueltiger Zeitstempel in scenario.json: " + field);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return fallback;
        }
        return value.asString();
    }

    /** Findet eine Szenarioressource im Klassenpfad (fuer mitgelieferte Fixtures). */
    public static Path classpathScenario(String scenarioId) {
        var url = FixtureData.class.getResource("/fixtures/" + scenarioId + "/scenario.json");
        if (url == null) {
            return null;
        }
        try {
            return Path.of(url.toURI()).getParent();
        } catch (java.net.URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }

    /** Prueft, ob eine Szenariokennung pfadsicher ist. */
    public static boolean isValidScenarioId(String scenarioId) {
        return scenarioId != null && scenarioId.matches("[a-z0-9][a-z0-9-]{1,63}");
    }

    /** Hilfsmittel fuer Tests: liest eine Ressource aus dem Klassenpfad als Text. */
    public static String readClasspathText(String resource) {
        try (InputStream in = FixtureData.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }
}
