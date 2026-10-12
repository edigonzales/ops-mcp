package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.jenkins.BuildParameter;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.RepositoryDescriptor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests des Jenkins-JSON-Parsers. Alle Beispiele sind synthetisch und ohne echte Kennungen. */
class JenkinsJsonParserTest {

    private static final JsonMapper MAPPER = YamlSupport.jsonMapper();

    private final JenkinsJsonParser parser = new JenkinsJsonParser(MAPPER);

    private static final BuildRef REF = new BuildRef("apps-deploy", List.of("folder", "app"), 42);

    private static final String SHA_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA_B = "89abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parsesCompleteBuildMetadata() {
        String json = """
                {
                  "displayName": "app #42",
                  "building": false,
                  "result": "FAILURE",
                  "timestamp": 1759312800000,
                  "duration": 95000,
                  "estimatedDuration": 120000,
                  "parameters": [
                    {"name": "NAMESPACE", "value": "fixture-integration"},
                    {"name": "IMAGE_TAG", "value": "registry.example.invalid/sogis/app:1.2.3"},
                    {"name": "SECRET_TOKEN", "value": "canary-value-1234567890"}
                  ],
                  "actions": [
                    {"_class": "hudson.plugins.git.util.BuildData",
                     "remoteUrls": ["https://git.example.invalid/sogis/openshift-templates.git"],
                     "lastBuiltRevision": {"SHA1": "%s",
                       "branch": [{"name": "refs/remotes/origin/main", "SHA1": "%s"}]}},
                    {"_class": "hudson.plugins.git.util.BuildData",
                     "remoteUrls": ["git@git.example.invalid:sogis/other-repo.git"],
                     "lastBuiltRevision": {"SHA1": "%s",
                       "branch": [{"name": "refs/remotes/origin/develop"}]}}
                  ]
                }
                """.formatted(SHA_A, SHA_A, SHA_B);

        BuildSnapshot snapshot = parser.parseBuildMetadata(json, REF,
                List.of("NAMESPACE", "IMAGE_TAG"));

        assertEquals(REF, snapshot.ref());
        assertEquals("app #42", snapshot.displayName());
        assertEquals(Boolean.FALSE, snapshot.building());
        assertEquals("FAILURE", snapshot.result());
        assertEquals(Instant.parse("2025-10-01T10:00:00Z"), snapshot.startedAt());
        assertTrue(snapshot.timestampAvailable());
        assertEquals(95000L, snapshot.durationMillis());
        assertEquals(120000L, snapshot.estimatedDurationMillis());
        assertTrue(snapshot.metadataAvailable());
        assertTrue(snapshot.isFinished());
        assertTrue(snapshot.isFailed());
        assertFalse(snapshot.isAborted());

        List<String> adopted = snapshot.parameters().stream().map(BuildParameter::name).toList();
        assertEquals(List.of("NAMESPACE", "IMAGE_TAG"), adopted);
        assertEquals("fixture-integration", snapshot.targetNamespaceHint());
        assertEquals(List.of("registry.example.invalid/sogis/app:1.2.3"), snapshot.imageReferences());
        assertTrue(snapshot.parameterNamesSeen().containsAll(List.of("NAMESPACE", "IMAGE_TAG", "SECRET_TOKEN")));

        // Der Wert eines nicht erlaubten Parameters erscheint nirgends.
        assertFalse(snapshot.toString().contains("canary-value-1234567890"));

        assertEquals(2, snapshot.scmRepositories().size());
        RepositoryDescriptor first = snapshot.scmRepositories().get(0);
        assertEquals("sogis/openshift-templates", first.repositoryIdHint());
        assertEquals("main", first.branch());
        assertEquals(SHA_A, first.commit());
        RepositoryDescriptor second = snapshot.scmRepositories().get(1);
        assertEquals("sogis/other-repo", second.repositoryIdHint());
        assertEquals("develop", second.branch());
        assertEquals(SHA_B, second.commit());
    }

    @Test
    void missingFieldsDoNotFailButAreNoted() {
        BuildSnapshot snapshot = parser.parseBuildMetadata("{}", REF, List.of());

        assertFalse(snapshot.metadataAvailable());
        assertFalse(snapshot.timestampAvailable());
        assertNull(snapshot.startedAt());
        assertNull(snapshot.building());
        assertNull(snapshot.result());
        assertNull(snapshot.displayName());
        assertTrue(snapshot.notes().size() >= 2);
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("timestamp")));
        assertFalse(snapshot.isFailed());
        assertNull(snapshot.finishedAt());
    }

    @Test
    void partiallyMissingFieldsAreReportedIndividually() {
        BuildSnapshot snapshot = parser.parseBuildMetadata("{\"displayName\":\"app\",\"building\":true}", REF,
                List.of());

        assertTrue(snapshot.metadataAvailable());
        assertFalse(snapshot.timestampAvailable());
        assertEquals(Boolean.TRUE, snapshot.building());
        assertNull(snapshot.result());
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("duration")));
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("estimatedDuration")));
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("timestamp")));
    }

    @Test
    void implausibleTimestampIsNotInventedAsStartTime() {
        BuildSnapshot snapshot = parser.parseBuildMetadata("{\"building\":false,\"timestamp\":1759312800}", REF,
                List.of());

        assertFalse(snapshot.timestampAvailable());
        assertNull(snapshot.startedAt());
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("Millisekundenwert")));
    }

    @Test
    void allowedParameterWithSecretValueIsRedacted() {
        String json = """
                {"parameters":[{"name":"APPLICATION_PASSWORD","value":"password=supersecretvalue123"}]}
                """;

        BuildSnapshot snapshot = parser.parseBuildMetadata(json, REF, List.of("APPLICATION_PASSWORD"));

        assertEquals(1, snapshot.parameters().size());
        BuildParameter parameter = snapshot.parameters().get(0);
        assertTrue(parameter.redacted());
        assertFalse(parameter.value().contains("supersecretvalue123"));
        assertTrue(parameter.value().contains("redigiert"));
    }

    @Test
    void parametersFromActionsAreAlsoConsidered() {
        String json = """
                {"actions":[{"_class":"hudson.model.ParametersAction",
                  "parameters":[{"name":"NAMESPACE","value":"fixture-2"}]}]}
                """;

        BuildSnapshot snapshot = parser.parseBuildMetadata(json, REF, List.of("NAMESPACE"));

        assertEquals("fixture-2", snapshot.parameterMap().get("NAMESPACE").value());
        assertEquals(List.of("NAMESPACE"), snapshot.parameterNamesSeen());
    }

    @Test
    void scmRepositoriesAreKeptSeparateAndDeduplicated() {
        String json = """
                {
                  "actions": [
                    {"_class": "hudson.plugins.git.util.BuildData",
                     "remoteUrls": ["https://git.example.invalid/sogis/openshift-templates.git"],
                     "lastBuiltRevision": {"SHA1": "%s"},
                     "buildsByBranchName": {
                       "refs/remotes/origin/release": {"SHA1": "%s", "branchName": "refs/remotes/origin/release"}
                     }},
                    {"_class": "hudson.plugins.git.util.BuildData",
                     "remoteUrls": {"origin": "https://git.example.invalid/sogis/second-repo.git"},
                     "lastBuiltRevision": {"SHA1": "%s"}},
                    {"_class": "hudson.model.ParametersAction",
                     "parameters": [{"name": "NAMESPACE", "value": "fixture"}]}
                  ]
                }
                """.formatted(SHA_A, SHA_B, SHA_B);

        List<RepositoryDescriptor> repositories = parser.parseScmRepositories(json);

        assertEquals(3, repositories.size());
        assertEquals(List.of("sogis/openshift-templates", "sogis/openshift-templates", "sogis/second-repo"),
                repositories.stream().map(RepositoryDescriptor::repositoryIdHint).toList());
        assertEquals("release", repositories.get(1).branch());
        assertEquals(SHA_B, repositories.get(1).commit());
        assertEquals("sogis/second-repo", repositories.get(2).repositoryIdHint());
    }

    @Test
    void shaFromFreeTextIsNotUsedAsRevision() {
        String json = """
                {
                  "actions": [
                    {"_class": "hudson.plugins.git.util.BuildData", "remoteUrls": [],
                     "description": "Checked out %s in workspace"},
                    {"_class": "hudson.model.ParametersAction",
                     "parameters": [{"name": "NAMESPACE", "value": "%s"}]}
                  ]
                }
                """.formatted(SHA_A, SHA_B);

        assertTrue(parser.parseScmRepositories(json).isEmpty());

        BuildSnapshot snapshot = parser.parseBuildMetadata(json, REF, List.of("NAMESPACE"));
        assertTrue(snapshot.scmRepositories().isEmpty());
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("SCM")));
    }

    @Test
    void lastFailedIsNotLastUnsuccessful() {
        String onlyUnsuccessful = """
                {"lastUnsuccessful": {"number": 7, "result": "ABORTED", "building": false}}
                """;
        JenkinsParseException missing = assertThrows(JenkinsParseException.class,
                () -> parser.parseLastFailed(onlyUnsuccessful, "apps-deploy", List.of("folder", "app")));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());
        assertTrue(missing.getMessage().contains("kein fehlgeschlagener Build bekannt"));

        String onlyFailed = """
                {"lastFailed": {"number": 42, "result": "FAILURE", "building": false}}
                """;
        JenkinsParseException otherDirection = assertThrows(JenkinsParseException.class,
                () -> parser.parseLastUnsuccessful(onlyFailed, "apps-deploy", List.of("folder", "app")));
        assertEquals(ErrorCode.NOT_FOUND, otherDirection.code());
        assertTrue(otherDirection.getMessage().contains("kein nicht erfolgreicher Build bekannt"));

        String both = """
                {"lastFailed": {"number": 42, "result": "FAILURE", "building": false},
                 "lastUnsuccessful": {"number": 41, "result": "ABORTED", "building": false}}
                """;
        BuildSelection failed = parser.parseLastFailed(both, "apps-deploy", List.of("folder", "app"));
        BuildSelection unsuccessful = parser.parseLastUnsuccessful(both, "apps-deploy", List.of("folder", "app"));

        assertEquals(42, failed.buildNumber());
        assertEquals("FAILURE", failed.result());
        assertEquals(41, unsuccessful.buildNumber());
        assertEquals("ABORTED", unsuccessful.result());
        assertTrue(failed.queriedLastFailed());
        assertTrue(failed.reason().contains("lastUnsuccessful"));
        assertTrue(unsuccessful.reason().contains("abgebrochene"));
        assertEquals(42, failed.toBuildRef().buildNumber());
    }

    @Test
    void nullLastFailedMeansNotFound() {
        JenkinsParseException exception = assertThrows(JenkinsParseException.class,
                () -> parser.parseLastFailed("{\"lastFailed\": null}", "apps-deploy", List.of("app")));

        assertEquals(ErrorCode.NOT_FOUND, exception.code());
    }

    @Test
    void brokenLastFailedFieldIsAParseError() {
        JenkinsParseException exception = assertThrows(JenkinsParseException.class,
                () -> parser.parseLastFailed("{\"lastFailed\": {\"result\": \"FAILURE\"}}", "apps-deploy",
                        List.of("app")));

        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, exception.code());
    }

    @Test
    void invalidJsonIsAParseError() {
        String[] broken = {"not json at all", "", "   ", "[1,2,3]", "{\"a\": }", "null"};
        for (String json : broken) {
            JenkinsParseException exception = assertThrows(JenkinsParseException.class,
                    () -> parser.parseBuildMetadata(json, REF, List.of()), "Eingabe: " + json);
            assertEquals(ErrorCode.SOURCE_PARSE_ERROR, exception.code());
        }
    }

    @Test
    void htmlBodyIsAParseError() {
        String html = """
                <!DOCTYPE html><html><head><title>Anmeldung</title></head>
                <body><form action="/j_spring_security_check"></form></body></html>
                """;

        assertTrue(JenkinsJsonParser.looksLikeHtml(html));
        assertFalse(JenkinsJsonParser.looksLikeHtml("{\"building\":false}"));

        JenkinsParseException exception = assertThrows(JenkinsParseException.class,
                () -> parser.parseBuildMetadata(html, REF, List.of()));
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, exception.code());
        assertTrue(exception.getMessage().contains("Login-HTML"));
    }

    @Test
    void jsonContainingHtmlLookingTextIsStillParsed() {
        assertFalse(JenkinsJsonParser.looksLikeHtml("{\"displayName\": \"<html> im Namen\"}"));

        BuildSnapshot snapshot = parser.parseBuildMetadata(
                "{\"displayName\": \"<html> im Namen\", \"building\": false}", REF, List.of());

        assertEquals("<html> im Namen", snapshot.displayName());
        assertTrue(snapshot.metadataAvailable());
    }

    @Test
    void unknownFieldsAreIgnoredWithoutInventingValues() {
        BuildSnapshot snapshot = parser.parseBuildMetadata(
                "{\"unknownField\": {\"nested\": 1}, \"building\": false, \"actions\": \"kein Array\"}", REF,
                List.of());

        assertNotNull(snapshot);
        assertEquals(Boolean.FALSE, snapshot.building());
        assertNull(snapshot.result());
        assertFalse(snapshot.timestampAvailable());
        assertTrue(snapshot.scmRepositories().isEmpty());
    }
}
