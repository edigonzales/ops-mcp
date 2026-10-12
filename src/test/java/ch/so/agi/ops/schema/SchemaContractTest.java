package ch.so.agi.ops.schema;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.snapshot.ManifestCodec;
import ch.so.agi.ops.snapshot.SnapshotManifest;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vertragstests fuer die JSON-Schemas in {@code schemas/}.
 *
 * <p>Geprueft wird mit {@code com.networknt:json-schema-validator} (Jackson 3) gegen die
 * Schemadateien des Projekts und die synthetischen Beispiele unter
 * {@code src/test/resources/schema/}. Es findet kein Netzzugriff statt: Die Schemas werden
 * lokal gelesen, enthalten keine externen {@code $ref} und werden gegen die lokal
 * registrierte Dialektversion Draft 2020-12 geprueft.</p>
 *
 * <p>Die Beispiele sind synthetisch. Sie enthalten keine echten Systemadressen,
 * Benutzerkennungen, Tokens oder Logkopien.</p>
 */
class SchemaContractTest {

    private static final Path PROJECT_DIR = Path.of(System.getProperty("opsagent.projectDir", "."));

    private static final String CONFIG_SCHEMA = "config-v1.schema.json";
    private static final String SNAPSHOT_SCHEMA = "snapshot-v1.schema.json";
    private static final String DIAGNOSIS_SCHEMA = "diagnosis-v1.schema.json";

    private static final JsonMapper JSON = YamlSupport.jsonMapper();
    private static final YAMLMapper YAML = YamlSupport.strictMapper();

    /** Draft 2020-12 mit aktiven format-Pruefungen (RFC3339-Zeitstempel), ohne Netz. */
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

    // ---------------------------------------------------------------- Diagnosis

    @Test
    void diagnosisBeispielAusAbschnitt61IstGueltig() {
        assertValid(diagnosisSchema(), diagnosisExample(), "diagnosis-example.json");
    }

    @Test
    void diagnosisMitExecutedTrueIstUngueltig() {
        JsonNode document = diagnosisExample();
        document.get("recommendations").get(0).asObject().put("executed", true);
        assertInvalid(diagnosisSchema(), document, "diagnosis-example.json mit executed: true", "executed");
    }

    @Test
    void diagnosisMitProzentKonfidenzIstUngueltig() {
        JsonNode document = diagnosisExample();
        document.get("hypotheses").get(0).asObject().put("confidence", 87);
        assertInvalid(diagnosisSchema(), document, "diagnosis-example.json mit confidence in einer Hypothese",
                "confidence");
    }

    @Test
    void diagnosisMitUnbekannterBewertungIstUngueltig() {
        JsonNode document = diagnosisExample();
        document.asObject().put("assessment", "sehr_sicher");
        assertInvalid(diagnosisSchema(), document, "diagnosis-example.json mit unbekannter Bewertung", "assessment");
    }

    @Test
    void diagnosisMitNamespacetemErweiterungsfeldIstGueltig() {
        JsonNode document = diagnosisExample();
        document.asObject().put("x-runbook-version", "runbook-fixture-1");
        assertValid(diagnosisSchema(), document, "diagnosis-example.json mit x--Erweiterungsfeld");
    }

    // ------------------------------------------------------------- Konfiguration

    @Test
    void configFixtureBeispielIstGueltig() {
        assertValid(configSchema(), configExample(), "config-fixture-example.yaml");
    }

    @Test
    void configMitUnbekanntemTopLevelSchluesselIstUngueltig() {
        JsonNode config = configExample();
        config.asObject().put("unknownSection", true);
        assertInvalid(configSchema(), config, "config-fixture-example.yaml mit unbekanntem Top-Level-Schluessel",
                "unknownSection");
    }

    @Test
    void configOhneFixtureAbschnittImFixtureModusIstUngueltig() {
        JsonNode config = configExample();
        config.asObject().remove("fixture");
        assertInvalid(configSchema(), config, "config-fixture-example.yaml ohne fixture im Modus fixture", "fixture");
    }

    @Test
    void configMitFixtureAbschnittImLiveModusIstUngueltig() {
        JsonNode config = configExample();
        config.asObject().put("mode", "live");
        assertInvalid(configSchema(), config, "config-fixture-example.yaml mit fixture im Modus live", "fixture");
    }

    @Test
    void configMitUeberschrittenemHardlimitIstUngueltig() {
        JsonNode config = configExample();
        config.get("limits").asObject().put("maxPods", 17);
        assertInvalid(configSchema(), config, "config-fixture-example.yaml mit maxPods ueber dem Hardlimit", "maxPods");
    }

    @Test
    void configMitUnvollstaendigemExplizitemProxyIstUngueltig() {
        JsonNode config = configExample();
        config.get("jenkins").get("apps-deploy").get("proxy").asObject().put("mode", "explicit");
        assertInvalid(configSchema(), config, "config-fixture-example.yaml mit mode: explicit ohne port", "port");
    }

    // ---------------------------------------------------------------- Snapshot

    @Test
    void snapshotBeispielIstGueltig() {
        assertValid(snapshotSchema(), snapshotExample(), "snapshot-example.json");
    }

    @Test
    void snapshotOhneCollectionStatusIstUngueltig() {
        JsonNode manifest = snapshotExample();
        manifest.asObject().remove("collectionStatus");
        assertInvalid(snapshotSchema(), manifest, "snapshot-example.json ohne collectionStatus", "collectionStatus");
    }

    @Test
    void snapshotMitPfadTraversalImLocatorIstUngueltig() {
        JsonNode manifest = snapshotExample();
        manifest.get("evidenceIndex").get(0).get("locator").asObject().put("path", "../ausserhalb.txt");
        assertInvalid(snapshotSchema(), manifest, "snapshot-example.json mit Pfad-Traversal im Locator", "locator");
    }

    @Test
    void snapshotMitUnbekanntemStatusIstUngueltig() {
        JsonNode manifest = snapshotExample();
        manifest.get("sourceResults").get(0).asObject().put("status", "collected");
        assertInvalid(snapshotSchema(), manifest, "snapshot-example.json mit unbekanntem Quellenstatus", "status");
    }

    @Test
    void snapshotMitUngueltigemZeitstempelIstUngueltig() {
        JsonNode manifest = snapshotExample();
        manifest.asObject().put("collectionStartedAt", "2026-10-01 10:00");
        assertInvalid(snapshotSchema(), manifest, "snapshot-example.json mit ungueltigem Zeitstempel",
                "collectionStartedAt");
    }

    /**
     * Gegenprobe ausserhalb des Schemas: Das Beispiel muss auch vom echten ManifestCodec
     * gelesen werden koennen. Damit ist belegt, dass Schema und Implementierung denselben
     * Manifestvertrag beschreiben.
     */
    @Test
    void snapshotBeispielIstVomManifestCodecLesbar() {
        JsonNode manifest = snapshotExample();
        String snapshotId = manifest.get("snapshotId").asString();
        SnapshotManifest parsed = new ManifestCodec(JSON).read(manifest, snapshotId);
        assertEquals(snapshotId, parsed.snapshotId());
        assertEquals(CollectionStatus.COMPLETE, parsed.collectionStatus());
        assertEquals("fixture-integration", parsed.namespace());
        assertEquals(8, parsed.evidenceIndex().size());
        assertEquals("EV-0004", parsed.evidenceIndex().get(3).id());
    }

    // ------------------------------------------------------------------ Helfer

    private static Schema configSchema() {
        return schema(CONFIG_SCHEMA);
    }

    private static Schema snapshotSchema() {
        return schema(SNAPSHOT_SCHEMA);
    }

    private static Schema diagnosisSchema() {
        return schema(DIAGNOSIS_SCHEMA);
    }

    /** Liest ein Schema aus dem Projektverzeichnis; es wird nichts nachgeladen. */
    private static Schema schema(String fileName) {
        Path file = PROJECT_DIR.resolve("schemas").resolve(fileName);
        assertTrue(Files.isRegularFile(file), "Schema nicht gefunden: " + file.toAbsolutePath());
        try {
            return REGISTRY.getSchema(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Schema nicht lesbar: " + file, ex);
        }
    }

    private static JsonNode configExample() {
        return YAML.readTree(readResource("config-fixture-example.yaml"));
    }

    private static JsonNode snapshotExample() {
        return JSON.readTree(readResource("snapshot-example.json"));
    }

    private static JsonNode diagnosisExample() {
        return JSON.readTree(readResource("diagnosis-example.json"));
    }

    private static String readResource(String name) {
        InputStream resource = SchemaContractTest.class.getResourceAsStream("/schema/" + name);
        assertNotNull(resource, "Testressource fehlt: /schema/" + name);
        try (InputStream in = resource) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Testressource nicht lesbar: /schema/" + name, ex);
        }
    }

    private static void assertValid(Schema schema, JsonNode instance, String label) {
        List<com.networknt.schema.Error> errors = schema.validate(instance);
        assertTrue(errors.isEmpty(), () -> label + " sollte gueltig sein, gemeldet wurde: " + render(errors));
    }

    private static void assertInvalid(Schema schema, JsonNode instance, String label, String expectedFragment) {
        List<com.networknt.schema.Error> errors = schema.validate(instance);
        assertFalse(errors.isEmpty(), label + " sollte ungueltig sein, wurde aber akzeptiert.");
        String rendered = render(errors);
        assertTrue(rendered.contains(expectedFragment),
                label + " wurde aus einem anderen Grund abgewiesen als erwartet (erwartet: '"
                        + expectedFragment + "'): " + rendered);
    }

    private static String render(List<com.networknt.schema.Error> errors) {
        return errors.stream()
                .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                .collect(Collectors.joining(" | "));
    }
}
