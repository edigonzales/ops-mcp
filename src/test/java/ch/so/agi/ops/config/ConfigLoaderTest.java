package ch.so.agi.ops.config;

import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des strengen Konfigurationsladers: unbekannte Schluessel, Typen, Pfadabkuerzungen,
 * Hardlimits, Modustrennung und die Trennung von oberer Berechtigungsgrenze und
 * Anwendungseintrag.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Baut eine minimale Fixturekonfiguration. Abschnitte, die {@code extra} selbst
     * ueberschreibt, werden aus der Basis entfernt, damit keine doppelten YAML-Schluessel
     * entstehen (die der strenge Parser korrekt abweisen wuerde).
     */
    private static String minimal(String extra) {
        String base = """
                schemaVersion: 1
                mode: fixture
                storage:
                  root: "./state"
                security:
                  allowPersonalCredentials: false
                  modelDataPolicy: internal-approved-only
                  identityMode: restricted
                limits: {}
                clusters: {}
                jenkins: {}
                repositories: {}
                applications: {}
                fixture:
                  root: "./fixtures"
                  scenario: t01-liveness-startup
                  clock: fixed
                  fixedNow: "2026-10-01T10:10:00Z"
                """;
        for (String section : new String[] { "storage", "security", "limits", "clusters", "jenkins",
                "repositories", "applications", "fixture" }) {
            if (containsSection(extra, section)) {
                base = stripSection(base, section);
            }
        }
        return base + extra;
    }

    private static boolean containsSection(String text, String section) {
        return java.util.regex.Pattern.compile("(?m)^" + section + ":").matcher(text).find();
    }

    /** Entfernt einen Top-Level-Abschnitt inklusive seiner eingerueckten Folgezeilen. */
    private static String stripSection(String text, String section) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean skipping = false;
        for (String line : lines) {
            if (line.startsWith(section + ":")) {
                skipping = true;
                continue;
            }
            if (skipping) {
                if (line.startsWith(" ") || line.isBlank()) {
                    continue;
                }
                skipping = false;
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    @Test
    @DisplayName("Minimale Fixturekonfiguration laedt und loest relative Pfade gegen die Datei auf")
    void minimalFixtureConfigLoads() throws IOException {
        Path file = write(minimal(""));
        OpsConfig config = new ConfigLoader().load(file);
        assertEquals(DataMode.FIXTURE, config.mode());
        assertEquals(1, config.schemaVersion());
        assertEquals(tempDir.resolve("state").normalize(), config.storageRoot());
        assertEquals(tempDir.resolve("fixtures").normalize(), config.fixture().root());
        assertEquals(file.toAbsolutePath().normalize(), config.sourceFile());
        assertTrue(config.isFixture());
        assertFalse(config.isLive());
    }

    @Test
    @DisplayName("Unbekannter Top-Level-Schluessel wird abgewiesen")
    void unknownTopLevelKeyIsRejected() throws IOException {
        Path file = write(minimal("unbekannt: 1\n"));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertEquals(ErrorCode.CONFIG_INVALID, ex.code());
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("Unbekannter Konfigurationsschluessel")));
    }

    @Test
    @DisplayName("Unbekannter Schluessel in einem Abschnitt und in einer Zuordnung wird abgewiesen")
    void unknownNestedKeyIsRejected() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    unbekannt: true
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                        .anyMatch(p -> p.message().contains("clusters.fixture-cluster.unbekannt")),
                "Erwartet wurde ein Problem fuer clusters.fixture-cluster.unbekannt: " + ex.problems());
    }

    @Test
    @DisplayName("Falscher Typ wird abgewiesen")
    void wrongTypeIsRejected() throws IOException {
        Path file = write("""
                schemaVersion: "eins"
                mode: fixture
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertEquals(ErrorCode.CONFIG_INVALID, ex.code());
    }

    @Test
    @DisplayName("Unbekannter Modus wird abgewiesen")
    void unknownModeIsRejected() throws IOException {
        Path file = write("""
                schemaVersion: 1
                mode: produktiv
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("'fixture' oder 'live'")));
    }

    @Test
    @DisplayName("schemaVersion muss 1 sein")
    void schemaVersionMustBeOne() throws IOException {
        Path file = write("""
                schemaVersion: 2
                mode: fixture
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("schemaVersion")));
    }

    @Test
    @DisplayName("Ein fixture-Abschnitt ist im Livemodus nicht zulaessig")
    void fixtureSectionInLiveModeIsRejected() throws IOException {
        Path file = write("""
                schemaVersion: 1
                mode: live
                storage:
                  root: "./state"
                fixture:
                  root: "./fixtures"
                  scenario: t01-liveness-startup
                  clock: fixed
                  fixedNow: "2026-10-01T10:10:00Z"
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("Modus 'live' nicht zulaessig")));
    }

    @Test
    @DisplayName("Im Fixturemodus fehlt der fixture-Abschnitt")
    void missingFixtureSectionIsReported() throws IOException {
        Path file = write("""
                schemaVersion: 1
                mode: fixture
                storage:
                  root: "./state"
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertEquals(ErrorCode.CONFIG_INCOMPLETE, ex.code());
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("fixture-Abschnitt")));
    }

    @Test
    @DisplayName("Hardlimits werden erzwungen")
    void hardLimitsAreEnforced() throws IOException {
        Path file = write(minimal("""
                limits:
                  collectTimeoutSeconds: 600
                  maxPods: 500
                  maxEvents: 100000
                  maxRequests: 10000
                  maxEvidenceResponseBytes: 1048576
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        List<String> fields = ex.problems().stream().map(ch.so.agi.ops.domain.OpsError::sourceId).toList();
        assertTrue(fields.contains("limits.collectTimeoutSeconds"), fields.toString());
        assertTrue(fields.contains("limits.maxPods"), fields.toString());
        assertTrue(fields.contains("limits.maxEvents"), fields.toString());
        assertTrue(fields.contains("limits.maxRequests"), fields.toString());
        assertTrue(fields.contains("limits.maxEvidenceResponseBytes"), fields.toString());
    }

    @Test
    @DisplayName("Fehlende Limits werden aus den dokumentierten Standardwerten ergaenzt")
    void defaultLimitsAreApplied() throws IOException {
        Path file = write(minimal(""));
        OpsConfig config = new ConfigLoader().load(file);
        assertEquals(90, config.limits().collectTimeoutSeconds());
        assertEquals(8, config.limits().maxPods());
        assertEquals(32768, config.limits().maxEvidenceResponseBytes());
    }

    @Test
    @DisplayName("Nur ~, $HOME und gesetzte ${NAME}-Variablen sind als Pfadabkuerzung zulaessig")
    void onlyDocumentedPathShortcutsAreAllowed() throws IOException {
        Path file = write(minimal("""
                storage:
                  root: "${OPS_AGENT_TESTVAR_NICHT_GESETZT}/state"
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                        .anyMatch(p -> p.message().contains("ist nicht gesetzt")),
                ex.problems().toString());
    }

    @Test
    @DisplayName("Kommandosubstitution in einer Pfadangabe wird abgewiesen")
    void commandSubstitutionIsRejected() throws IOException {
        Path file = write(minimal("""
                storage:
                  root: "$(whoami)/state"
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                        .anyMatch(p -> p.message().contains("Kommandosubstitution")),
                ex.problems().toString());
    }

    @Test
    @DisplayName("Shell-Erweiterungen wie ${NAME:-default} werden abgewiesen")
    void shellExpansionIsRejected() throws IOException {
        Path file = write(minimal("""
                storage:
                  root: "${HOME:-/tmp}/state"
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                        .anyMatch(p -> p.message().contains("Shell-Erweiterungen")),
                ex.problems().toString());
    }

    @Test
    @DisplayName("${HOME} wird aufgeloest")
    void homeVariableIsResolved() throws IOException {
        Path file = write(minimal("""
                storage:
                  root: "${HOME}/ops-agent-test-state"
                """));
        OpsConfig config = new ConfigLoader().load(file);
        assertTrue(config.storageRoot().toString()
                .endsWith("ops-agent-test-state"));
    }

    @Test
    @DisplayName("Ein Namespace ausserhalb der oberen Grenze wird abgewiesen")
    void namespaceOutsideAllowedListIsRejected() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    allowedNamespaces:
                      - erlaubt
                applications:
                  app:
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: nicht-erlaubt
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertEquals(ErrorCode.SCOPE_DENIED, ex.code());
        assertTrue(ex.problems().stream().anyMatch(p -> p.code() == ErrorCode.SCOPE_DENIED
                && p.message().contains("allowedNamespaces")), ex.problems().toString());
    }

    @Test
    @DisplayName("Ein Jobpfad ausserhalb der oberen Grenze wird abgewiesen")
    void jobPathOutsideAllowedListIsRejected() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    allowedNamespaces:
                      - ns
                jenkins:
                  apps-deploy:
                    baseUrl: "https://jenkins.fixture.invalid"
                    token:
                      environmentVar: OPS_AGENT_FIXTURE_TOKEN
                    allowedJobPaths:
                      - ["erlaubt", "job"]
                applications:
                  app:
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: ns
                        workload:
                          kind: Deployment
                          name: x
                        deployment:
                          jenkins: apps-deploy
                          jobPath: ["anderer", "job"]
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.code() == ErrorCode.SCOPE_DENIED));
    }

    @Test
    @DisplayName("Eine Datei ausserhalb der Repository-Allowlist wird abgewiesen")
    void fileOutsideRepositoryAllowListIsRejected() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    allowedNamespaces:
                      - ns
                repositories:
                  deployment-config:
                    repositoryId: sogis/openshift-templates
                    localPath: "./repo"
                    allowedFiles:
                      - templates/a.yaml
                applications:
                  app:
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: ns
                        workload:
                          kind: Deployment
                          name: x
                        deployment:
                          jenkins: apps-deploy
                          jobPath: ["f", "j"]
                        configuration:
                          repository: deployment-config
                          templateFiles:
                            - templates/geheim.yaml
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.code() == ErrorCode.SCOPE_DENIED));
    }

    @Test
    @DisplayName("Ein unzulaessiger relativer Pfad in allowedFiles wird abgewiesen")
    void unsafeRelativePathIsRejected() throws IOException {
        Path file = write(minimal("""
                repositories:
                  deployment-config:
                    repositoryId: sogis/openshift-templates
                    localPath: "./repo"
                    allowedFiles:
                      - "templates/../../etc/passwd"
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                .anyMatch(p -> p.message().contains("Unzulaessiger relativer Pfad")));
    }

    @Test
    @DisplayName("Ein Jobpfadsegment mit Schraegstrich wird abgewiesen")
    void jobPathSegmentWithSlashIsRejected() throws IOException {
        Path file = write(minimal("""
                jenkins:
                  apps-deploy:
                    baseUrl: "https://jenkins.fixture.invalid"
                    token:
                      environmentVar: OPS_AGENT_FIXTURE_TOKEN
                    allowedJobPaths:
                      - ["folder/sub", "job"]
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("weder '/' noch '..'")));
    }

    @Test
    @DisplayName("Nicht unterstuetzte Workload-Art wird abgewiesen")
    void unsupportedWorkloadKindIsRejected() throws IOException {
        Path file = write(minimal("""
                applications:
                  app:
                    environments:
                      integration:
                        cluster: fixture-cluster
                        namespace: ns
                        workload:
                          kind: StatefulSet
                          name: x
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream()
                .anyMatch(p -> p.message().contains("Nicht unterstuetzte Workload-Art")));
    }

    @Test
    @DisplayName("Eine Secret-Referenz braucht file oder environmentVar; Werte stehen nie im YAML")
    void secretReferenceIsRequired() throws IOException {
        Path file = write(minimal("""
                jenkins:
                  apps-deploy:
                    baseUrl: "https://jenkins.fixture.invalid"
                    token: {}
                """));
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.problems().stream().anyMatch(p -> p.message().contains("environmentVar")));
    }

    @Test
    @DisplayName("schemaVersion fehlt")
    void missingSchemaVersionIsReported() throws IOException {
        Path file = write("""
                mode: fixture
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertEquals(ErrorCode.CONFIG_INCOMPLETE, ex.code());
    }

    @Test
    @DisplayName("Mehrere YAML-Dokumente werden abgewiesen")
    void multipleDocumentsAreRejected() throws IOException {
        Path file = write("""
                schemaVersion: 1
                mode: fixture
                ---
                schemaVersion: 1
                mode: live
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.getMessage().contains("mehrere YAML-Dokumente")
                        || ex.getMessage().contains("Trailing token"),
                "Ein zweites Dokument muss abgewiesen werden: " + ex.getMessage());
    }

    @Test
    @DisplayName("Unzulaessige YAML-Tags werden abgewiesen")
    void unsafeYamlTagsAreRejected() throws IOException {
        Path file = write("""
                schemaVersion: 1
                mode: !!java.lang.String fixture
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> new ConfigLoader().load(file));
        assertTrue(ex.getMessage().contains("unzulaessige YAML-Tags"));
    }

    @Test
    @DisplayName("Nicht vorhandene Konfigurationsdatei wird gemeldet")
    void missingFileIsReported() {
        ConfigException ex = assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(tempDir.resolve("gibt-es-nicht.yaml")));
        assertEquals(ErrorCode.CONFIG_INVALID, ex.code());
        assertTrue(ex.getMessage().contains("nicht gefunden"));
    }

    @Test
    @DisplayName("Namespacete x--Erweiterungsfelder sind zulaessig")
    void extensionFieldsAreAllowed() throws IOException {
        Path file = write(minimal("x-team: gis\n"));
        OpsConfig config = new ConfigLoader().load(file);
        assertNotNull(config);
    }

    @Test
    @DisplayName("identityMode wird uebernommen und ist eine Betreiberdeklaration")
    void identityModeIsCarried() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                    context: "ctx"
                    identityMode: personal-prototype
                    allowedNamespaces:
                      - ns
                """));
        OpsConfig config = new ConfigLoader().load(file);
        assertEquals("personal-prototype", config.cluster("fixture-cluster").identityMode());
        assertFalse(config.security().allowPersonalCredentials());
    }

    @Test
    @DisplayName("Weder ocExecutable noch kubeconfig sind gesetzt; isComplete ist false")
    void incompleteClusterIsRecognized() throws IOException {
        Path file = write(minimal("""
                clusters:
                  fixture-cluster:
                    server: "https://fixture.invalid:6443"
                """));
        OpsConfig config = new ConfigLoader().load(file);
        assertFalse(config.cluster("fixture-cluster").isComplete());
        assertNull(config.cluster("fixture-cluster").context());
    }
}
