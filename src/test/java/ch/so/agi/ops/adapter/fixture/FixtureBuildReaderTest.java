package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft den Fixtureadapter der Jenkins-Quelle: Metadaten, Console-Log mit Trunkierung,
 * {@code lastFailed} aus {@code job.json} und die erklaerten Transportfehler.
 */
class FixtureBuildReaderTest {

    private static final JsonMapper MAPPER = FixtureHarness.mapper();

    @Test
    @DisplayName("Build 42: Metadaten, Parameter, SCM-Commit und Image-Referenz")
    void buildMetadataIsParsedFromTheSyntheticAnswer() {
        BuildSnapshot build = FixtureHarness.build("t01-liveness-startup", MAPPER);

        assertEquals("FAILURE", build.result());
        assertEquals(Boolean.FALSE, build.building());
        assertEquals(42, build.ref().buildNumber());
        assertEquals("apps-deploy", build.ref().instanceId());
        assertEquals(List.of("fixture-folder", "modelfinder"), build.ref().jobPath());
        assertTrue(build.metadataAvailable());
        assertTrue(build.timestampAvailable(), "Der synthetische Build traegt einen Zeitstempel.");
        assertEquals(java.time.Instant.ofEpochMilli(1789000000000L), build.startedAt());
        assertEquals(612000L, build.durationMillis());
        assertNotNull(build.finishedAt(), "Start und Dauer eines abgeschlossenen Builds ergeben ein Ende.");

        assertTrue(build.parameterNamesSeen().containsAll(List.of("TARGET_NAMESPACE", "IMAGE_TAG",
                "CONFIG_REVISION")), "Die gesehenen Parameternamen muessen erhalten bleiben: "
                        + build.parameterNamesSeen());
        assertTrue(build.parameterMap().containsKey("IMAGE_TAG"));
        assertTrue(build.imageReferences().contains("2026.10.1"),
                "Der erlaubte Image-Parameter muss als Image-Referenz erkannt werden: " + build.imageReferences());
        // TARGET_NAMESPACE ist in der Fixturekonfiguration NICHT erlaubt; der Zielnamespace
        // darf deshalb nicht aus einem nicht erlaubten Parameter stammen.
        assertNull(build.targetNamespaceHint(),
                "Der Zielnamespace darf nur aus erlaubten Parametern stammen.");

        assertFalse(build.scmRepositories().isEmpty(), "Die SCM-Angaben muessen ausgewertet werden.");
        var repository = build.scmRepositories().stream()
                .filter(candidate -> candidate.commit() != null)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Kein SCM-Commit ausgewertet: " + build.scmRepositories()));
        assertEquals("sogis/openshift-templates", repository.repositoryIdHint());
        assertEquals("c0563524e661fe31844e23943c2e9bf1b4cda0e1", repository.commit());
        assertEquals("main", repository.branch());
    }

    @Test
    @DisplayName("Nur erlaubte Parameternamen werden uebernommen, die uebrigen nur als Namen gefuehrt")
    void onlyAllowedParametersAreAdopted() {
        // Die Fixturekonfiguration erlaubt IMAGE_TAG, PROBE_PATH, INITIAL_DELAY_SECONDS und
        // FAILURE_THRESHOLD - ausdruecklich NICHT TARGET_NAMESPACE.
        BuildSnapshot build = FixtureHarness.build("t01-liveness-startup", MAPPER);
        assertTrue(build.parameterMap().containsKey("IMAGE_TAG"), "Der erlaubte Parameter wird uebernommen.");
        assertFalse(build.parameterMap().containsKey("TARGET_NAMESPACE"),
                "Nicht erlaubte Parameter duerfen nicht uebernommen werden: " + build.parameterMap().keySet());
        assertTrue(build.parameterNamesSeen().contains("TARGET_NAMESPACE"),
                "Gesehene Parameternamen werden auch ohne Uebernahme gefuehrt.");
        assertNull(build.targetNamespaceHint(),
                "Der Zielnamespace stammt nur aus erlaubten Parametern und bleibt hier unbekannt.");
    }

    @Test
    @DisplayName("lastFailed wird aus job.json aufgeloest, nicht mit lastUnsuccessful verwechselt")
    void lastFailedIsResolvedFromTheJobDocument() {
        OpsConfig config = FixtureHarness.config("t01-liveness-startup");
        var target = FixtureHarness.target("t01-liveness-startup", config);
        SourceResult<BuildSelection> result = FixtureHarness.buildReader(config, "t01-liveness-startup", MAPPER)
                .resolveBuild(target, BuildSelector.lastFailed());

        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(42, result.data().buildNumber());
        assertEquals("apps-deploy", result.data().instanceId());
        assertEquals(List.of("fixture-folder", "modelfinder"), result.data().jobPath());
    }

    @Test
    @DisplayName("Eine konkrete Buildnummer bleibt unveraendert")
    void explicitBuildNumberIsKept() {
        OpsConfig config = FixtureHarness.config("t01-liveness-startup");
        var target = FixtureHarness.target("t01-liveness-startup", config);
        SourceResult<BuildSelection> result = FixtureHarness.buildReader(config, "t01-liveness-startup", MAPPER)
                .resolveBuild(target, BuildSelector.ofNumber(42));
        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(42, result.data().buildNumber());
        assertFalse(result.data().queriedLastFailed());
    }

    @Test
    @DisplayName("Kleines Console-Log wird vollstaendig gelesen, inklusive Fehleranker")
    void smallConsoleLogIsReadCompletely() {
        ConsoleLogEvidence log = FixtureHarness.consoleLog("t01-liveness-startup", MAPPER);
        assertFalse(log.truncated(), "Ein kleines Log wird nicht abgeschnitten.");
        assertNull(log.headRequestedFromByte());
        assertNull(log.tailRequestedFromByte());
        assertEquals("", log.truncatedReason());
        assertEquals(42, log.ref().buildNumber());
        assertTrue(log.lines().stream().anyMatch(line -> line.contains("timed out waiting for the condition")),
                "Der Rolloutfehler muss im Ausschnitt stehen.");
        assertTrue(log.errorAnchors().stream().anyMatch(anchor -> anchor.line().contains("ERROR")),
                "Der Fehleranker ERROR muss erkannt werden: " + log.errorAnchors());
        assertFalse(log.timestampsAvailable(), "Die synthetischen Zeilen tragen keine Zeitstempel.");
        assertTrue(log.bytesRead() > 0);
        assertEquals(log.totalBytesAvailable().longValue(), log.bytesRead(),
                "Ohne Trunkierung entspricht die gelesene Menge der Gesamtmenge.");
    }

    @Test
    @DisplayName("Grosses Console-Log wird ehrlich abgeschnitten und behaelt den Endausschnitt")
    void largeConsoleLogIsTruncatedHonestly() throws java.io.IOException {
        // Ein bewusst grosses Console-Log in einer Kopie des Szenarios. Die Rohdatei des
        // eingecheckten Szenarios wird dabei nicht veraendert.
        java.nio.file.Path tempRoot = java.nio.file.Files.createTempDirectory("fixture-buildlog-");
        java.nio.file.Path scenarioDir = tempRoot.resolve("t01-liveness-startup");
        FixtureHarness.copyTree(FixtureHarness.scenarioDir("t01-liveness-startup"), scenarioDir);
        java.nio.file.Files.writeString(scenarioDir.resolve("jenkins/build-42-console.txt"), largeConsoleLog());

        OpsConfig config = FixtureHarness.configAt(tempRoot, "t01-liveness-startup", 512L);
        var target = FixtureHarness.target("t01-liveness-startup", config);
        SourceResult<ConsoleLogEvidence> result = FixtureHarness
                .buildReaderAt(config, "t01-liveness-startup", MAPPER)
                .collectConsoleLog(target, target.budget());

        assertEquals(SourceStatus.TRUNCATED, result.status());
        ConsoleLogEvidence log = result.data();
        assertTrue(log.truncated(), "Das Log muss als abgeschnitten gekennzeichnet sein.");
        assertFalse(log.truncatedReason().isBlank(), "Der Grund der Trunkierung muss genannt werden.");
        assertEquals(0L, log.headRequestedFromByte());
        assertTrue(log.tailRequestedFromByte() > 0, "Der Endausschnitt muss belegt sein.");
        assertTrue(log.lines().stream().anyMatch(line -> line.contains("BUILD-ENDE-FEHLER")),
                "Der wichtige Fehler nahe dem Ende muss im Ausschnitt bleiben.");
        assertTrue(log.errorAnchors().stream().anyMatch(anchor -> anchor.line().contains("BUILD-ENDE-FEHLER")));
        assertTrue(log.totalBytesAvailable() > log.bytesRead(),
                "Die Gesamtmenge muss groesser sein als die gelesene Menge.");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Bytebudget")),
                "Der Hinweis muss die Trunkierung ausdruecklich nennen.");
    }

    @Test
    @DisplayName("T12a: 403 ergibt FORBIDDEN/UNREACHABLE und keinen BuildSnapshot")
    void forbiddenJenkinsAnswerIsAnExplainedError() {
        OpsConfig config = FixtureHarness.config("t12a-jenkins-forbidden");
        var target = FixtureHarness.target("t12a-jenkins-forbidden", config);

        SourceResult<BuildSnapshot> metadata = FixtureHarness
                .buildReader(config, "t12a-jenkins-forbidden", MAPPER).collectMetadata(target, target.budget());
        assertEquals(SourceStatus.UNREACHABLE, metadata.status());
        assertEquals(ErrorCode.FORBIDDEN, metadata.errors().get(0).code());
        assertFalse(metadata.data().metadataAvailable(), "Es darf kein scheinbar gueltiger BuildSnapshot entstehen.");
        assertTrue(metadata.notes().stream().anyMatch(note -> note.contains("kein BuildSnapshot")),
                "Der Hinweis muss ausdruecklich sagen, dass kein Snapshot geliefert wird.");

        SourceResult<ConsoleLogEvidence> log = FixtureHarness
                .buildReader(config, "t12a-jenkins-forbidden", MAPPER).collectConsoleLog(target, target.budget());
        assertEquals(SourceStatus.UNREACHABLE, log.status());
        assertEquals(ErrorCode.FORBIDDEN, log.errors().get(0).code());
        assertTrue(log.data().lines().isEmpty(), "Es wird kein Log erfunden.");

        SourceResult<BuildSelection> selection = FixtureHarness
                .buildReader(config, "t12a-jenkins-forbidden", MAPPER)
                .resolveBuild(target, BuildSelector.lastFailed());
        assertEquals(SourceStatus.UNREACHABLE, selection.status(),
                "Auch die Buildaufloesung darf keinen stillen Erfolg liefern.");
    }

    @Test
    @DisplayName("Ein fehlendes Console-Log ergibt NOT_PRESENT mit NOT_FOUND")
    void missingConsoleLogIsReported() {
        OpsConfig config = FixtureHarness.config("t08-jenkins-before-deploy");
        var target = FixtureHarness.target("t08-jenkins-before-deploy", config);
        // Im Szenario heisst das Log build-42-console.txt; eine andere Nummer fehlt bewusst.
        var otherBuild = new ch.so.agi.ops.application.port.ResolvedTarget(target.targetId(), config,
                ch.so.agi.ops.domain.DataMode.FIXTURE, target.applicationId(), target.environment(),
                target.definition(), new ch.so.agi.ops.domain.jenkins.BuildRef("apps-deploy",
                        List.of("fixture-folder", "modelfinder"), 99),
                null, target.resolvedAt(), target.expiresAt(), target.budget(), List.of(), "t08-jenkins-before-deploy");
        SourceResult<ConsoleLogEvidence> result = FixtureHarness
                .buildReader(config, "t08-jenkins-before-deploy", MAPPER)
                .collectConsoleLog(otherBuild, otherBuild.budget());
        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertEquals(ErrorCode.NOT_FOUND, result.errors().get(0).code());
        assertTrue(result.data().lines().isEmpty());
    }

    private static String largeConsoleLog() {
        StringBuilder builder = new StringBuilder();
        builder.append("[2026-10-01T09:58:00.000Z] Started by user fixture-operator\n");
        for (int index = 0; index < 200; index++) {
            builder.append("[2026-10-01T09:58:").append(String.format("%02d", index % 60))
                    .append(".000Z] Waiting for deployment \"fixture-modelfinder\" rollout to finish: 0 of 1 "
                            + "updated replicas are available (Zeile ").append(index).append(")\n");
        }
        builder.append("[2026-10-01T10:08:00.000Z] ERROR: BUILD-ENDE-FEHLER: Rollout-Timeout nach 600s\n");
        return builder.toString();
    }
}
