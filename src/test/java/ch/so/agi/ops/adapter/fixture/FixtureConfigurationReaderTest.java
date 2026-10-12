package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft den Fixtureadapter der Konfigurationsquelle.
 *
 * <p>Belegt werden die vier verbindlichen Faelle: Commit A wird gelesen, ein fehlender Commit
 * ergibt {@code REVISION_UNAVAILABLE} ohne Ersatz, ein fehlender Commit in der Revision liest
 * gar nichts, und eine zusaetzlich gelesene Datei des heutigen Stands wird als
 * {@code REFERENCE_ONLY} gefuehrt.</p>
 */
class FixtureConfigurationReaderTest {

    private static final JsonMapper MAPPER = FixtureHarness.mapper();
    private static final String TEMPLATE = "t01-liveness-startup/repo/templates/modelfinder.yaml";
    private static final String ENV = "t01-liveness-startup/repo/env/integration.env";

    @Test
    @DisplayName("Commit A wird gelesen, ohne den Arbeitsbaum zu veraendern")
    void commitAIsReadFromTheRepository() {
        Path checkout = FixtureHarness.gitWorkspace("t06a-revision-available", MAPPER);
        SourceResult<ResolvedConfiguration> result = FixtureHarness.readConfiguration(
                "t06a-revision-available", checkout, MAPPER);

        assertEquals(SourceStatus.COLLECTED, result.status(), "Fehler: " + result.errors());
        ResolvedConfiguration configuration = result.data();
        assertEquals(ResolvedConfiguration.ConfigStatus.RESOLVED, configuration.status());
        assertEquals("sogis/openshift-templates", configuration.repositoryId());
        assertTrue(configuration.revision().hasCommit());

        List<FileReadResult> files = configuration.files();
        assertEquals(2, files.size(), "Beide erlaubten Dateien werden gelesen: " + files);
        for (FileReadResult file : files) {
            assertEquals(FileReadResult.FileStatus.READ, file.status(), "Datei nicht gelesen: " + file);
            assertFalse(file.lines().isEmpty(), "Die gelesene Datei darf nicht leer sein: " + file.relativePath());
            assertEquals(configuration.revision().commit(), file.commit(),
                    "Der Commit muss im Dateiergebnis erhalten bleiben: " + file);
        }
        FileReadResult template = fileWithRole(files, FileReadResult.FileRole.TEMPLATE);
        assertTrue(template.lines().stream().anyMatch(line -> line.contains("initialDelaySeconds: ${INITIAL_DELAY_SECONDS}")),
                "Der Inhalt muss aus dem Template am Commit stammen: " + template);
        FileReadResult parameters = fileWithRole(files, FileReadResult.FileRole.PARAMETERS);
        assertTrue(parameters.lines().stream().anyMatch(line -> line.contains("INITIAL_DELAY_SECONDS=10")),
                "Der Inhalt muss aus der Parameterdatei am Commit stammen: " + parameters);

        // Die Aufloesung stammt aus der gemeinsamen Logik des Live-Adapters.
        assertFalse(configuration.containerNames().isEmpty(),
                "Die Containernamen muessen aus dem Template aufgeloest werden.");
        assertTrue(configuration.workloads().containsKey("modelfinder"), "Workloads: " + configuration.workloads());
        // Die Aufloesung benennt die Proben nach ihrer Art (liveness/readiness).
        var probes = configuration.workloads().get("modelfinder").probes();
        var liveness = probes.values().stream()
                .filter(probe -> probe.kind() == ch.so.agi.ops.domain.k8s.ProbeConfig.ProbeKind.LIVENESS)
                .findFirst().orElse(null);
        assertNotNull(liveness, "Probes der aufgeloesten Konfiguration: " + probes);
        assertEquals(10, liveness.initialDelaySeconds());
        assertEquals(10, liveness.periodSeconds());
        assertEquals(3, liveness.failureThreshold());

        // Der Arbeitsbaum bleibt unveraendert: keine neuen Dateien, kein Checkout.
        assertTrue(FixtureHarness.gitWorkspace("t06a-revision-available", MAPPER).resolve(".git").toFile().exists());
    }

    @Test
    @DisplayName("Ueber die Revisionskopie wird derselbe Inhalt gelesen wie ueber Git")
    void repoFilesCopyMatchesGitContent() {
        SourceResult<ResolvedConfiguration> viaCopies = FixtureHarness.readConfiguration(
                "t06a-revision-available", null, MAPPER);
        SourceResult<ResolvedConfiguration> viaGit = FixtureHarness.readConfiguration(
                "t06a-revision-available", FixtureHarness.gitWorkspace("t06a-revision-available", MAPPER), MAPPER);

        assertEquals(SourceStatus.COLLECTED, viaCopies.status(), "Fehler: " + viaCopies.errors());
        assertEquals(SourceStatus.COLLECTED, viaGit.status(), "Fehler: " + viaGit.errors());
        assertEquals(hashes(viaGit.data()), hashes(viaCopies.data()),
                "Beide Wege muessen denselben bereinigten Inhalt liefern.");
    }

    @Test
    @DisplayName("Fehlender Commit ergibt REVISION_UNAVAILABLE ohne Ersatz durch einen anderen Commit")
    void missingCommitIsNotReplaced() {
        // T06b verlangt Commit B; im Szenario liegt nur die Revisionskopie von Commit A.
        SourceResult<ResolvedConfiguration> result = FixtureHarness.readConfiguration(
                "t06b-revision-missing", null, MAPPER);

        assertTrue(result.errors().stream().anyMatch(error -> error.code() == ErrorCode.REVISION_UNAVAILABLE),
                "Erwartet wird REVISION_UNAVAILABLE: " + result.errors());
        ResolvedConfiguration configuration = result.data();
        assertEquals(ResolvedConfiguration.ConfigStatus.UNAVAILABLE, configuration.status());
        assertTrue(configuration.workloads().isEmpty(),
                "Es darf keine Ersatzkonfiguration aus einem anderen Commit entstehen.");
        assertTrue(configuration.containerNames().isEmpty());
        assertTrue(configuration.files().stream()
                        .filter(file -> file.role() != FileReadResult.FileRole.REFERENCE_ONLY)
                        .allMatch(file -> file.status() != FileReadResult.FileStatus.READ),
                "Keine historische Datei darf als gelesen gelten: " + configuration.files());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("kein anderer Commit")),
                "Der Hinweis muss ausdruecklich sagen, dass kein Ersatzcommit verwendet wurde.");
    }

    @Test
    @DisplayName("revision.commit() == null liest keine Datei und meldet REVISION_UNKNOWN")
    void unknownRevisionReadsNothing() {
        OpsConfig config = FixtureHarness.config("t01-liveness-startup");
        var target = FixtureHarness.target("t01-liveness-startup", config);
        RevisionProvenance unknown = RevisionProvenance.unknown("sogis/openshift-templates",
                "Kein SCM-Beleg im Build.");
        SourceResult<ResolvedConfiguration> result = FixtureHarness
                .configurationReader(config, "t01-liveness-startup", MAPPER)
                .collect(target, unknown, target.budget());

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ErrorCode.REVISION_UNKNOWN, result.errors().get(0).code());
        ResolvedConfiguration configuration = result.data();
        assertEquals(ResolvedConfiguration.ConfigStatus.UNAVAILABLE, configuration.status());
        assertTrue(configuration.files().isEmpty(), "Ohne Commit wird keine Datei gelesen.");
        assertTrue(configuration.workloads().isEmpty());
        assertNull(configuration.revision().commit());
    }

    @Test
    @DisplayName("Eine zusaetzliche Datei des heutigen Stands wird als REFERENCE_ONLY gefuehrt")
    void additionalCurrentFileIsReferenceOnly() {
        SourceResult<ResolvedConfiguration> result = FixtureHarness.readConfiguration(
                "t06b-revision-missing", null, MAPPER);
        ResolvedConfiguration configuration = result.data();

        assertEquals(2, configuration.currentReferenceFiles().size(),
                "Beide Referenzdateien muessen gefuehrt werden: " + configuration.currentReferenceFiles());
        List<FileReadResult> references = configuration.files().stream()
                .filter(file -> file.role() == FileReadResult.FileRole.REFERENCE_ONLY)
                .toList();
        assertEquals(2, references.size(), "Die Referenzdateien muessen als solche markiert sein.");
        for (FileReadResult reference : references) {
            assertEquals(FileReadResult.FileStatus.READ, reference.status());
            assertNull(reference.commit(),
                    "Eine Referenzdatei hat keinen historischen Commit und ist kein Sollzustand.");
            assertTrue(reference.note().contains("kein historischer Sollzustand"),
                    "Die Rolle muss im Datensatz erklaert sein: " + reference.note());
        }
        // Der heutige Stand enthaelt andere Probe-Werte als der belegte Commit; genau das darf
        // nicht als historischer Sollzustand gelten.
        assertTrue(configuration.workloads().isEmpty(),
                "Aus REFERENCE_ONLY-Dateien darf keine historische Konfiguration entstehen.");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("nicht als historischer Sollzustand")),
                "Der Hinweis muss die Rolle der Referenzdatei nennen: " + result.notes());
    }

    private static FileReadResult fileWithRole(List<FileReadResult> files, FileReadResult.FileRole role) {
        return files.stream().filter(file -> file.role() == role).findFirst()
                .orElseThrow(() -> new AssertionError("Keine Datei mit Rolle " + role + ": " + files));
    }

    private static List<String> hashes(ResolvedConfiguration configuration) {
        return configuration.files().stream()
                .filter(file -> file.role() != FileReadResult.FileRole.REFERENCE_ONLY)
                .map(FileReadResult::sha256)
                .sorted()
                .toList();
    }
}
