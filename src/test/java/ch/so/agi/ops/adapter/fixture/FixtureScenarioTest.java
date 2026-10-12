package ch.so.agi.ops.adapter.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft die synthetischen Szenarien als Rohdatenbestand.
 *
 * <p>Fuer jedes Szenario wird belegt: {@code scenario.json} existiert, ist synthetisch
 * markiert, alle erwarteten Rohdateien sind vorhanden und gueltiges JSON - ausser den
 * ausdruecklich als ungueltig deklarierten Fehlervarianten. Zusaetzlich wird geprueft, dass
 * kein {@code .git}-Verzeichnis eingecheckt ist und keine offensichtlichen Zugangsdaten in
 * den Rohdaten stehen.</p>
 */
class FixtureScenarioTest {

    private static final JsonMapper MAPPER = FixtureHarness.mapper();

    static Stream<String> scenarioIds() {
        return FixtureHarness.scenarioIds().stream();
    }

    @ParameterizedTest(name = "{0}: scenario.json ist synthetisch und beschreibt die Erwartung")
    @MethodSource("scenarioIds")
    void scenarioDescriptorIsCompleteAndSynthetic(String scenarioId) {
        FixtureData scenario = FixtureHarness.scenarioData(scenarioId, MAPPER);
        assertEquals(scenarioId, scenario.id(), "Die Szenariokennung muss dem Verzeichnisnamen entsprechen.");
        assertTrue(scenario.synthetic(), "Jedes Szenario muss ausdruecklich als synthetisch markiert sein.");
        assertFalse(scenario.title().isBlank(), "Der Titel des Szenarios fehlt.");
        assertFalse(scenario.expectation().isBlank(), "Die fachliche Erwartung des Szenarios fehlt.");
        assertFalse(scenario.tags().isEmpty(), "Die Tags des Szenarios fehlen.");
        assertNotNull(scenario.instant("fixedNow"),
                "Ein fester Zeitpunkt (fixedNow) ist fuer reproduzierbare Ablaeufe erforderlich.");
    }

    @ParameterizedTest(name = "{0}: erwartete Rohdateien sind vorhanden und JSON gueltig")
    @MethodSource("scenarioIds")
    void expectedRawFilesArePresentAndParsable(String scenarioId) {
        FixtureData scenario = FixtureHarness.scenarioData(scenarioId, MAPPER);
        JsonNode descriptor = scenario.readJson("scenario.json");
        JsonNode expected = descriptor.get("expectedRawFiles");
        assertNotNull(expected, "expectedRawFiles fehlt in scenario.json.");
        assertTrue(expected.isArray() && expected.size() > 0, "expectedRawFiles ist leer.");

        Set<String> intentionallyInvalid = new HashSet<>(stringList(descriptor.get("rawFilesIntentionallyInvalid")));
        for (JsonNode entry : expected) {
            String relativePath = entry.asString();
            assertTrue(scenario.has(relativePath), scenarioId + ": erwartete Rohdatei fehlt: " + relativePath);
            if (!relativePath.endsWith(".json")) {
                assertFalse(intentionallyInvalid.contains(relativePath),
                        scenarioId + ": " + relativePath + " ist als ungueltig markiert, ist aber keine JSON-Datei.");
                continue;
            }
            String text;
            try {
                text = Files.readString(scenario.resolve(relativePath), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new AssertionError(scenarioId + ": Rohdatei nicht lesbar: " + relativePath, ex);
            }
            boolean parsable;
            try {
                parsable = MAPPER.readTree(text) != null;
            } catch (RuntimeException ex) {
                parsable = false;
            }
            if (intentionallyInvalid.contains(relativePath)) {
                assertFalse(parsable, scenarioId + ": " + relativePath
                        + " ist als absichtlich ungueltig deklariert, laesst sich aber parsen. "
                        + "Die Fehlervariante belegt so nichts.");
            } else {
                assertTrue(parsable, scenarioId + ": " + relativePath + " ist kein gueltiges JSON.");
            }
        }
    }

    @ParameterizedTest(name = "{0}: keine zusaetzlichen, nicht deklarierten Szenariodateien")
    @MethodSource("scenarioIds")
    void scenarioContainsNoUndeclaredFiles(String scenarioId) throws IOException {
        FixtureData scenario = FixtureHarness.scenarioData(scenarioId, MAPPER);
        Set<String> declared = new HashSet<>(stringList(scenario.readJson("scenario.json").get("expectedRawFiles")));
        declared.add("scenario.json");
        Set<String> actual = new HashSet<>();
        try (Stream<Path> stream = Files.walk(scenario.directory())) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> actual.add(scenario.directory().relativize(path).toString().replace('\\', '/')));
        }
        Set<String> undeclared = new HashSet<>(actual);
        undeclared.removeAll(declared);
        assertTrue(undeclared.isEmpty(), scenarioId + ": nicht deklarierte Szenariodateien: " + undeclared);
    }

    @Test
    @DisplayName("Kein Szenario enthaelt ein eingechecktes .git-Verzeichnis")
    void noScenarioShipsAGitDirectory() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String scenarioId : FixtureHarness.scenarioIds()) {
            Path directory = FixtureHarness.scenarioDir(scenarioId);
            try (Stream<Path> stream = Files.walk(directory)) {
                stream.filter(path -> path.getFileName().toString().equals(".git"))
                        .forEach(path -> offenders.add(directory.relativize(path).toString()));
            }
        }
        assertTrue(offenders.isEmpty(), "Eingecheckte .git-Verzeichnisse sind unerwuenscht: " + offenders);
    }

    @Test
    @DisplayName("Die Szenarien T01-T12 sind vollstaendig angelegt")
    void allRequiredScenariosExist() {
        List<String> ids = FixtureHarness.scenarioIds();
        List<String> required = List.of("t01-liveness-startup", "t02-readiness-only", "t03-wrong-probe-path",
                "t04-application-crash", "t05-history-lost", "t06a-revision-available", "t06b-revision-missing",
                "t06c-foreign-sha", "t07-probe-timeout", "t08-jenkins-before-deploy", "t09-sidecar-oom",
                "t09b-main-oom", "t09c-exit137", "t10-second-application", "t10b-deploymentconfig",
                "t11-ambiguous-revision", "t12a-jenkins-forbidden", "t12b-previous-log-missing",
                "t12c-invalid-json");
        for (String id : required) {
            assertTrue(ids.contains(id), "Pflichtszenario fehlt: " + id);
        }
    }

    @Test
    @DisplayName("Die Rohdaten enthalten keine offensichtlichen Zugangsdaten oder echten Hosts")
    void rawDataContainsNoCredentialsAndNoRealHosts() throws IOException {
        List<String> suspicious = List.of("BEGIN PRIVATE KEY", "AKIA", "ghp_", "xoxb-", "Bearer eyJ",
                "ocp.so.ch", "jenkins-agi-apps-production", "gretl.so.ch", "logit.io");
        List<String> findings = new ArrayList<>();
        for (String scenarioId : FixtureHarness.scenarioIds()) {
            Path directory = FixtureHarness.scenarioDir(scenarioId);
            try (Stream<Path> stream = Files.walk(directory)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (String needle : suspicious) {
                        if (text.contains(needle)) {
                            findings.add(scenarioId + "/" + directory.relativize(file) + " enthaelt '" + needle + "'");
                        }
                    }
                }
            }
        }
        assertTrue(findings.isEmpty(), "Verdaechtige Inhalte in Fixtures: " + findings);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isString()) {
                    result.add(item.asString());
                }
            });
        }
        return result;
    }
}
