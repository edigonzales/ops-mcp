package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.application.port.ResolvedTarget;
import ch.so.agi.ops.config.DeploymentDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.SecuritySettings;
import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des produktiven {@link JenkinsHttpReader} gegen einen lokalen Aufzeichnungsserver
 * ({@code 127.0.0.1}, Port {@code 0}). Es findet kein Zugriff auf echte Jenkins-Instanzen und
 * keine Firmen-DNS-Aufloesung statt; alle Kennungen und Token sind synthetisch.
 */
class JenkinsHttpReaderTest {

    private static final JsonMapper MAPPER = YamlSupport.jsonMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:05:00Z"), ZoneOffset.UTC);
    private static final String CANARY = "canary-token-9f3a1c7d5e2b4a68";
    private static final String JOB_PATH = "/job/folder/job/app";
    private static final Pattern ALLOWED_ENDPOINT =
            Pattern.compile("^/job/[^/]+(/job/[^/]+)*(/\\d+/(api/json|consoleText)|/api/json)$");

    private static final String METADATA_JSON = """
            {"displayName":"fixture-app #42","building":false,"result":"FAILURE",
             "timestamp":1759312800000,"duration":1000,"estimatedDuration":2000,
             "parameters":[{"name":"NAMESPACE","value":"fixture-integration"}]}
            """;

    private RecordingHttpServer server;
    private RecordingHttpServer foreign;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = RecordingHttpServer.start();
        foreign = RecordingHttpServer.start();
        tempDir = Files.createTempDirectory("ops-jenkins-reader-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
        foreign.close();
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Endpunkte, Methode, Encoding
    // ------------------------------------------------------------------

    @Test
    void collectMetadataUsesGetOnTheFixedEndpoint() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);
        JenkinsHttpReader reader = reader(config);

        SourceResult<BuildSnapshot> result = reader.collectMetadata(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(1, server.requestCount());
        RecordingHttpServer.Recorded request = server.requests().get(0);
        assertEquals("GET", request.method());
        assertEquals(JOB_PATH + "/42/api/json", request.rawPath());
        assertEquals("tree=" + JenkinsHttpReader.BUILD_TREE, request.query());
        assertTrue(request.authorization().startsWith("Bearer "));
        assertEquals("fixture-integration", result.data().targetNamespaceHint());
        assertEquals(42, result.data().ref().buildNumber());
        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(METADATA_JSON.getBytes(StandardCharsets.UTF_8).length, budget.inputBytesUsed());
    }

    @Test
    void folderJobSegmentsAreEncodedInTheRequestPath() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respondPrefix("/job/", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "my app"), 7, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        RecordingHttpServer.Recorded request = server.requests().get(0);
        assertEquals("/job/folder/job/my%20app/7/api/json", request.rawPath());
        assertEquals("/job/folder/job/my app/7/api/json", request.decodedPath());
        assertFalse(request.rawPath().contains(" "));
    }

    @Test
    void targetOfAnotherInstanceIsRejectedWithoutAnyRequest() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, "gretl", List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertEquals(0, server.requestCount());
    }

    @Test
    void jobPathOutsideTheAllowlistIsRejectedWithoutAnyRequest() {
        Path token = writeToken("token-value");
        OpsConfig config = fullConfig(server.baseUrl(), token, null, null, "direct", null, null, null, 5, 64 * 1024,
                List.of(List.of("other", "app")), List.of("NAMESPACE"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertEquals(0, server.requestCount());
    }

    // ------------------------------------------------------------------
    // Fehlervertrag
    // ------------------------------------------------------------------

    @Test
    void httpStatusCodesAreDistinguished() {
        record Case(int status, ErrorCode code, SourceStatus expectedStatus) {
        }
        List<Case> cases = List.of(
                new Case(401, ErrorCode.AUTH_REQUIRED, SourceStatus.NOT_ALLOWED),
                new Case(403, ErrorCode.FORBIDDEN, SourceStatus.NOT_ALLOWED),
                new Case(404, ErrorCode.NOT_FOUND, SourceStatus.NOT_PRESENT),
                new Case(407, ErrorCode.PROXY_AUTH_REQUIRED, SourceStatus.NOT_ALLOWED));

        for (Case testCase : cases) {
            Path token = writeToken("token-value");
            OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
            server.respond(JOB_PATH + "/42/api/json",
                    RecordingHttpServer.Response.of(testCase.status()).body("abgelehnt"));
            CollectionBudget budget = budget(60, 32L * 1024 * 1024);
            ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

            SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

            assertEquals(testCase.expectedStatus(), result.status(), "HTTP " + testCase.status());
            assertEquals(testCase.code(), result.errors().get(0).code(), "HTTP " + testCase.status());
        }
    }

    @Test
    void proxyAuthRequiredIsNotDescribedAsRbacProblem() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.of(407));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);
        OpsError error = result.errors().get(0);

        assertEquals(ErrorCode.PROXY_AUTH_REQUIRED, error.code());
        assertTrue(error.message().contains("407"));
        assertTrue(error.message().contains("Proxy"));
        for (String forbidden : List.of("RBAC", "OpenShift", "Rolle", "Namespace-Recht")) {
            assertFalse(error.message().contains(forbidden), "Meldung darf nicht '" + forbidden + "' nennen");
            assertFalse(error.suggestedAction().contains(forbidden));
        }
    }

    @Test
    void loginHtmlWithHttp200IsAParseError() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.html(
                "<html><body><form action=\"/j_spring_security_check\"></form></body></html>"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.UNREACHABLE, result.status());
        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, result.errors().get(0).code());
        assertTrue(result.errors().get(0).message().contains("Login-HTML"));
    }

    @Test
    void invalidJsonIsAParseErrorWithoutInventedValues() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json("{kaputt"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(ErrorCode.SOURCE_PARSE_ERROR, result.errors().get(0).code());
        assertFalse(result.data().metadataAvailable());
        assertFalse(result.data().timestampAvailable());
        assertNull(result.data().startedAt());
    }

    // ------------------------------------------------------------------
    // Weiterleitungen und Authentifizierung
    // ------------------------------------------------------------------

    @Test
    void redirectToForeignOriginIsRejectedAndNoAuthorizationIsSentThere() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, "svc-user", "direct", null, null, null, 5, 64 * 1024);
        String foreignUrl = foreign.baseUrl() + JOB_PATH + "/42/api/json";
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.redirect(302, foreignUrl));
        foreign.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.NOT_ALLOWED, result.status());
        assertEquals(ErrorCode.SCOPE_DENIED, result.errors().get(0).code());
        assertTrue(result.errors().get(0).message().contains("fremde Origin"));
        assertEquals(1, server.requestCount());
        assertEquals(0, foreign.requestCount(), "Der fremde Host darf keine Anfrage erhalten");
        assertNotNull(server.requests().get(0).authorization());
    }

    @Test
    void redirectToTheSameOriginIsFollowedWithAuthorization() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, "svc-user", "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.redirect(303, "/moved/api/json"));
        server.respond("/moved/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(2, server.requestCount());
        assertEquals("/moved/api/json", server.requests().get(1).rawPath());
        assertNotNull(server.requests().get(1).authorization());
        assertEquals(0, foreign.requestCount());
    }

    @Test
    void basicAuthorizationHeaderIsBuiltFromUsernameAndTokenFile() {
        Path token = writeToken("\n   token-from-file   \nzweite-zeile\n");
        OpsConfig config = config(server.baseUrl(), token, "svc-user", "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        reader(config).collectMetadata(target, budget);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("svc-user:token-from-file".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, server.requests().get(0).authorization());
    }

    @Test
    void bearerAuthorizationHeaderIsUsedWithoutUsername() {
        Path token = writeToken("token-from-file");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        reader(config).collectMetadata(target, budget);

        assertEquals("Bearer token-from-file", server.requests().get(0).authorization());
    }

    @Test
    void tokenCanComeFromAnEnvironmentVariable() {
        OpsConfig config = fullConfig(server.baseUrl(), null, "JENKINS_TEST_TOKEN", null, "direct", null, null,
                null, 5, 64 * 1024, List.of(), List.of("NAMESPACE"));
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);
        JenkinsHttpReader reader = new JenkinsHttpReader(config, "apps-deploy", CLOCK, MAPPER, null,
                name -> "JENKINS_TEST_TOKEN".equals(name) ? "env-token-value" : null);

        SourceResult<BuildSnapshot> result = reader.collectMetadata(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals("Bearer env-token-value", server.requests().get(0).authorization());
    }

    @Test
    void tokenCanComeFromTheProcessEnvironment() {
        String value = System.getenv("NO_PROXY");
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                "Der Testlauf setzt NO_PROXY nicht; die Prozessumgebung ist nicht pruefbar.");
        OpsConfig config = fullConfig(server.baseUrl(), null, "NO_PROXY", null, "direct", null, null, null, 5,
                64 * 1024, List.of(), List.of("NAMESPACE"));
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        reader(config).collectMetadata(target, budget);

        assertEquals("Bearer " + value.trim(), server.requests().get(0).authorization());
    }

    @Test
    void missingOrEmptyTokenIsConfigIncompleteAndCausesNoRequest() {
        OpsConfig withoutToken = config(server.baseUrl(), null, null, "direct", null, null, null, 5, 64 * 1024);
        OpsConfig emptyToken = config(server.baseUrl(), writeToken("\n   \n"), null, "direct", null, null, null, 5,
                64 * 1024);
        server.respondPrefix("/job/", RecordingHttpServer.Response.json(METADATA_JSON));

        for (OpsConfig config : List.of(withoutToken, emptyToken)) {
            CollectionBudget budget = budget(60, 32L * 1024 * 1024);
            ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

            SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

            assertEquals(SourceStatus.UNREACHABLE, result.status());
            assertEquals(ErrorCode.CONFIG_INCOMPLETE, result.errors().get(0).code());
        }
        assertEquals(0, server.requestCount());
    }

    @Test
    void tokenValueNeverAppearsInResultsOrMessages() {
        Path token = writeToken(CANARY);
        OpsConfig config = config(server.baseUrl(), token, "svc-user", "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json",
                RecordingHttpServer.Response.of(403).body("Zugriff verweigert fuer " + CANARY));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);
        JenkinsHttpReader reader = reader(config);

        SourceResult<BuildSnapshot> forbidden = reader.collectMetadata(target, budget);
        assertFalse(forbidden.toString().contains(CANARY));
        assertFalse(forbidden.errors().get(0).message().contains(CANARY));

        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.of(407).body(CANARY));
        SourceResult<BuildSnapshot> proxy = reader.collectMetadata(target, budget);
        assertFalse(proxy.toString().contains(CANARY));

        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.html("<html>" + CANARY + "</html>"));
        SourceResult<BuildSnapshot> html = reader.collectMetadata(target, budget);
        assertFalse(html.toString().contains(CANARY));

        server.respond(JOB_PATH + "/42/api/json",
                RecordingHttpServer.Response.redirect(302, foreign.baseUrl() + "/x?t=" + CANARY));
        SourceResult<BuildSnapshot> redirect = reader.collectMetadata(target, budget);
        assertFalse(redirect.toString().contains(CANARY));
        assertFalse(redirect.errors().get(0).message().contains(CANARY));

        assertFalse(reader.transportConfiguration().toString().contains(CANARY));
    }

    // ------------------------------------------------------------------
    // Zeitbudget, Proxy, CA
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void requestTimeoutIsReportedAsRequestTimeout() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 1, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON).delay(2000));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.UNREACHABLE, result.status());
        assertEquals(ErrorCode.REQUEST_TIMEOUT, result.errors().get(0).code());
        assertTrue(result.errors().get(0).retryable());
    }

    @Test
    void budgetExhaustionStopsBeforeAnyRequest() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        CollectionBudget budget = budget(0, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<BuildSnapshot> result = reader(config).collectMetadata(target, budget);

        assertEquals(SourceStatus.NOT_ATTEMPTED, result.status());
        assertEquals(ErrorCode.BUDGET_EXCEEDED, result.errors().get(0).code());
        assertEquals(0, server.requestCount());
    }

    @Test
    void proxyModesAreConfiguredExplicitlyAndIgnoreEnvironmentVariables() {
        Path token = writeToken("token-value");
        OpsConfig direct = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        OpsConfig explicit = config(server.baseUrl(), token, null, "explicit", "127.0.0.1", 3128, null, 5, 64 * 1024);

        JenkinsHttpReader directReader = reader(direct);
        JenkinsHttpReader explicitReader = reader(explicit);

        assertEquals("direct", directReader.transportConfiguration().get("proxyMode"));
        assertEquals(Proxy.Type.DIRECT,
                directReader.proxySelector().select(URI.create("http://127.0.0.1:1/x")).get(0).type());
        assertEquals(Boolean.FALSE, directReader.transportConfiguration().get("environmentProxyVariablesEvaluated"));

        Map<String, Object> map = explicitReader.transportConfiguration();
        assertEquals("explicit", map.get("proxyMode"));
        assertEquals("127.0.0.1", map.get("proxyHost"));
        assertEquals(3128, map.get("proxyPort"));
        Proxy proxy = explicitReader.proxySelector().select(URI.create("http://jenkins.example.invalid/job/app")).get(0);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertEquals(new InetSocketAddress("127.0.0.1", 3128), proxy.address());
        assertEquals(0, server.requestCount());
    }

    @Test
    void invalidAdditionalCaFileIsAnExplainedErrorWithoutDisablingTls() throws IOException {
        Path brokenCa = tempDir.resolve("broken-ca.pem");
        Files.writeString(brokenCa, "kein zertifikat\n");
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, brokenCa, 5, 64 * 1024);
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);
        JenkinsHttpReader reader = new JenkinsHttpReader(config, "apps-deploy", CLOCK, MAPPER);

        SourceResult<BuildSnapshot> result = reader.collectMetadata(target, budget);

        assertEquals(SourceStatus.UNREACHABLE, result.status());
        assertEquals(ErrorCode.CONFIG_INVALID, result.errors().get(0).code());
        assertTrue(result.errors().get(0).message().contains("CA-Datei"));
        assertEquals(0, server.requestCount());
        assertEquals(Boolean.FALSE, reader.transportConfiguration().get("tlsVerificationDisabled"));
        assertEquals(Boolean.FALSE, reader.transportConfiguration().get("insecureSkipVerifySupported"));
    }

    @Test
    void validAdditionalCaFileIsAddedWithoutDisablingVerification() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Assumptions.assumeTrue(Files.isExecutable(keytool), "keytool ist nicht verfuegbar");
        Path keyStore = tempDir.resolve("fixture-ca.p12");
        Path caFile = tempDir.resolve("fixture-ca.pem");
        run(keytool, "-genkeypair", "-alias", "fixture-ca", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "changeit",
                "-dname", "CN=fixture-ca", "-validity", "1");
        run(keytool, "-exportcert", "-alias", "fixture-ca", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-rfc", "-file", caFile.toString());

        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, caFile, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);
        JenkinsHttpReader reader = new JenkinsHttpReader(config, "apps-deploy", CLOCK, MAPPER);

        SourceResult<BuildSnapshot> result = reader.collectMetadata(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        assertEquals(Boolean.TRUE, reader.transportConfiguration().get("additionalCaConfigured"));
        assertEquals(Boolean.FALSE, reader.transportConfiguration().get("tlsVerificationDisabled"));
        assertEquals(1, server.requestCount());
    }

    // ------------------------------------------------------------------
    // lastFailed
    // ------------------------------------------------------------------

    @Test
    void lastFailedIsResolvedExactlyOnceAndThenUnused() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/api/json", RecordingHttpServer.Response.json(
                "{\"lastFailed\":{\"number\":42,\"result\":\"FAILURE\",\"building\":false}}"));
        server.respond(JOB_PATH + "/42/api/json", RecordingHttpServer.Response.json(METADATA_JSON));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget probe = target(config, List.of("folder", "app"), 1, budget);
        JenkinsHttpReader reader = reader(config);

        SourceResult<BuildSelection> selection = reader.resolveBuild(probe,
                new BuildSelector(BuildSelector.Kind.LAST_FAILED, null, List.of("folder", "app"), null));

        assertEquals(SourceStatus.COLLECTED, selection.status());
        assertEquals(42, selection.data().buildNumber());
        assertEquals("FAILURE", selection.data().result());
        assertTrue(selection.data().queriedLastFailed());
        assertEquals(1, server.requestCount());
        assertEquals("tree=" + JenkinsHttpReader.LAST_FAILED_TREE, server.requests().get(0).query());

        ResolvedTarget resolved = target(config, List.of("folder", "app"), 42, budget);
        assertEquals(SourceStatus.COLLECTED, reader.collectMetadata(resolved, budget).status());

        assertEquals(1, server.requestsWithQueryContaining("lastFailed").size(),
                "lastFailed darf nur einmal aufgeloest werden");
        assertEquals(2, server.requestCount());
        assertEquals(JOB_PATH + "/42/api/json", server.requests().get(1).rawPath());
        assertFalse(server.requests().get(1).query().contains("lastFailed"));
    }

    @Test
    void lastFailedWithoutFailedBuildIsNotFoundAndNeverFallsBackToLastUnsuccessful() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/api/json", RecordingHttpServer.Response.json(
                "{\"lastUnsuccessful\":{\"number\":7,\"result\":\"ABORTED\",\"building\":false}}"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget probe = target(config, List.of("folder", "app"), 1, budget);

        SourceResult<BuildSelection> selection = reader(config).resolveBuild(probe,
                new BuildSelector(BuildSelector.Kind.LAST_FAILED, null, List.of("folder", "app"), null));

        assertTrue(selection.status().isGap(), "Ein nicht nutzbares Ergebnis muss als Luecke gelten");
        assertEquals(ErrorCode.NOT_FOUND, selection.errors().get(0).code());
        assertTrue(selection.errors().get(0).message().contains("kein fehlgeschlagener Build bekannt"));
    }

    @Test
    void explicitBuildNumberNeedsNoNetworkCallButReservesARequest() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget probe = target(config, List.of("folder", "app"), 1, budget);

        SourceResult<BuildSelection> selection = reader(config).resolveBuild(probe, BuildSelector.ofNumber(42));

        assertEquals(SourceStatus.COLLECTED, selection.status());
        assertEquals(42, selection.data().buildNumber());
        assertEquals(0, server.requestCount());
        assertEquals(1, budget.requestsUsed());
    }

    // ------------------------------------------------------------------
    // Console-Log
    // ------------------------------------------------------------------

    @Test
    void smallConsoleLogIsReadCompletelyWithoutRangeRequests() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        String log = "starte deployment\nwarte auf rollout\nrollout abgeschlossen\n";
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(log));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertEquals(List.of("starte deployment", "warte auf rollout", "rollout abgeschlossen"), evidence.lines());
        assertEquals(1, evidence.firstLineNumber());
        assertFalse(evidence.truncated());
        assertTrue(evidence.truncatedReason().isEmpty());
        assertFalse(evidence.timestampsAvailable());
        assertEquals((long) log.getBytes(StandardCharsets.UTF_8).length, evidence.totalBytesAvailable());
        assertTrue(evidence.errorAnchors().isEmpty());
        assertEquals(2, server.requestCount());
        assertEquals("HEAD", server.requests().get(0).method());
        assertEquals("GET", server.requests().get(1).method());
        assertNull(server.requests().get(1).range(), "Ein kleines Log wird ohne Range vollstaendig gelesen");
        assertTrue(budget.inputBytesUsed() > 0);
    }

    @Test
    void exhaustedInputBudgetStopsTheConsoleLogWithoutAnyRequest() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respondPrefix("/job/", RecordingHttpServer.Response.text("eine zeile\n"));
        CollectionBudget budget = budget(60, 1000);
        budget.recordInputBytes(1000);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.NOT_ATTEMPTED, result.status());
        assertEquals(ErrorCode.BUDGET_EXCEEDED, result.errors().get(0).code());
        assertTrue(result.data().lines().isEmpty());
        assertEquals(0, server.requestCount());
    }

    @Test
    void emptyConsoleLogIsReportedAsNotPresent() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(""));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.NOT_PRESENT, result.status());
        assertTrue(result.data().lines().isEmpty());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("leer")));
    }

    @Test
    void largeConsoleLogKeepsTheErrorNearTheEnd() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 4096);
        String log = largeLog();
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(log));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.TRUNCATED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertTrue(evidence.truncated());
        assertTrue(evidence.truncatedReason().contains("Bytebudget"));
        assertTrue(evidence.lines().stream().anyMatch(line -> line.startsWith("step 1 ok")),
                "Der Kopfausschnitt muss enthalten sein");
        assertTrue(evidence.lines().stream().anyMatch(line -> line.contains("ERROR: rollout failed")),
                "Der Fehler nahe dem Ende muss enthalten sein");
        assertEquals("ERROR", evidence.errorAnchors().stream()
                .filter(anchor -> anchor.line().contains("ERROR: rollout failed"))
                .map(ConsoleLogEvidence.ErrorAnchor::marker)
                .findFirst().orElse(null));
        assertTrue(evidence.errorAnchors().stream()
                .anyMatch(anchor -> anchor.line().contains("AbortException")));
        assertTrue(evidence.lines().stream().anyMatch(line -> line.startsWith("[...")),
                "Eine Luecke muss im Ergebnis ausdruecklich markiert sein");
        int errorIndex = indexOfLine(evidence.lines(), "ERROR: rollout failed");
        assertTrue(errorIndex > evidence.lines().size() - 12, "Der Fehler muss nahe am Ende stehen");
        assertFalse(evidence.timestampsAvailable());
    }

    @Test
    void largeButWithinBudgetLogKeepsTheErrorNearTheEnd() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 1500; i++) {
            builder.append("step ").append(i).append(" ok\n");
        }
        builder.append("ERROR: pipeline failed at the very end\n");
        String log = builder.toString();
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(log));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.COLLECTED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertFalse(evidence.truncated(), "Ein Log innerhalb des Budgets wird vollstaendig gelesen");
        assertEquals(1501, evidence.lines().size());
        assertEquals("ERROR: pipeline failed at the very end", evidence.lines().get(1500));
        ConsoleLogEvidence.ErrorAnchor anchor = evidence.errorAnchors().stream()
                .filter(candidate -> candidate.line().contains("pipeline failed"))
                .findFirst()
                .orElseThrow();
        assertEquals("ERROR", anchor.marker());
        assertEquals(1501, anchor.lineNumber(), "Bei vollstaendigem Lesen sind die Zeilennummern exakt");
    }

    @Test
    void errorInTheMiddleIsFoundByTheStreamingScan() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 4096);
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            builder.append("step ").append(i).append(" ok\n");
        }
        builder.append("ERROR: failure in the middle of the log\n");
        for (int i = 101; i <= 2000; i++) {
            builder.append("step ").append(i).append(" ok\n");
        }
        String log = builder.toString();
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(log));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.TRUNCATED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertTrue(evidence.truncated());
        assertTrue(evidence.truncatedReason().contains("Mittelteil"));
        assertTrue(evidence.lines().stream().anyMatch(line -> line.contains("ERROR: failure in the middle")),
                "Die Fehlerzeile aus dem abgesuchten Mittelteil muss im Ergebnis stehen");
        ConsoleLogEvidence.ErrorAnchor anchor = evidence.errorAnchors().stream()
                .filter(candidate -> candidate.line().contains("failure in the middle"))
                .findFirst()
                .orElseThrow();
        assertTrue(anchor.lineNumber() > 0,
                "Fehlerzeilen aus dem fortlaufend abgesuchten Mittelteil haben eine Zeilennummer");
    }

    @Test
    void consoleLogWithoutByteBudgetLeftIsMarkedAsPrefixOnly() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 4096);
        String log = largeLog();
        server.respond(JOB_PATH + "/42/consoleText",
                RecordingHttpServer.Response.text(log).headNotSupported().noRangeSupport().omitContentLength());
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.TRUNCATED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertTrue(evidence.truncated());
        assertTrue(evidence.truncatedReason().contains("Bytebudget"));
        assertTrue(evidence.truncatedReason().contains("NICHT gelesen"));
        assertEquals(1, evidence.firstLineNumber());
        assertTrue(evidence.lines().get(0).startsWith("step 1 ok"));
        assertFalse(evidence.lines().stream().anyMatch(line -> line.contains("ERROR: rollout failed")),
                "Ohne Budget darf das Buildende nicht behauptet werden");
    }

    @Test
    void consoleLogWithoutRangeSupportReportsThatTheEndWasNotExamined() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 4096);
        String log = largeLog();
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(log).noRangeSupport());
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertEquals(SourceStatus.TRUNCATED, result.status());
        ConsoleLogEvidence evidence = result.data();
        assertTrue(evidence.truncatedReason().contains("Range"));
        assertTrue(evidence.truncatedReason().contains("NICHT"));
        assertFalse(evidence.lines().stream().anyMatch(line -> line.contains("ERROR: rollout failed")),
                "Ohne Range darf das Buildende nicht behauptet werden");
    }

    @Test
    void consoleLogTimestampsAreDetectedButNeverInvented() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, null, "direct", null, null, null, 5, 64 * 1024);
        server.respond(JOB_PATH + "/42/consoleText", RecordingHttpServer.Response.text(
                "2026-10-01 10:00:01 starte deployment\n2026-10-01 10:00:05 ERROR rollout fehlgeschlagen\n"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget target = target(config, List.of("folder", "app"), 42, budget);

        SourceResult<ConsoleLogEvidence> result = reader(config).collectConsoleLog(target, budget);

        assertTrue(result.data().timestampsAvailable());
        assertEquals(1, result.data().errorAnchors().size());
        assertEquals(2, result.data().errorAnchors().get(0).lineNumber());
    }

    // ------------------------------------------------------------------
    // Read-only-Nachweis
    // ------------------------------------------------------------------

    @Test
    void onlyReadEndpointsAndMethodsAreUsed() {
        Path token = writeToken("token-value");
        OpsConfig config = config(server.baseUrl(), token, "svc-user", "direct", null, null, null, 5, 4096);
        server.respondPrefix("/job/", RecordingHttpServer.Response.json(
                "{\"building\":false,\"result\":\"SUCCESS\",\"lastFailed\":{\"number\":42,\"result\":\"FAILURE\"}}"));
        server.respondPrefix("/job/folder/job/two words/42/consoleText",
                RecordingHttpServer.Response.text("eine zeile\n"));
        CollectionBudget budget = budget(60, 32L * 1024 * 1024);
        ResolvedTarget probe = target(config, List.of("folder", "two words"), 1, budget);
        JenkinsHttpReader reader = reader(config);

        reader.resolveBuild(probe, new BuildSelector(BuildSelector.Kind.LAST_FAILED, null,
                List.of("folder", "two words"), null));
        ResolvedTarget resolved = target(config, List.of("folder", "two words"), 42, budget);
        reader.collectMetadata(resolved, budget);
        reader.collectConsoleLog(resolved, budget);

        assertTrue(server.requestCount() >= 3);
        for (RecordingHttpServer.Recorded request : server.requests()) {
            assertTrue(Set.of("GET", "HEAD").contains(request.method()), "Methode: " + request.method());
            assertTrue(ALLOWED_ENDPOINT.matcher(request.rawPath()).matches(), "Pfad: " + request.rawPath());
            for (String forbidden : List.of("config.xml", "script", "doDelete", "crumb", "quietDown", "restart",
                    "reload", "submit", "/build", "/stop", "workspace")) {
                assertFalse(request.rawPath().contains(forbidden), "Verbotener Pfadteil: " + forbidden);
            }
        }
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private static String largeLog() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            builder.append("step ").append(i).append(" ok\n");
        }
        builder.append("ERROR: rollout failed: deployment \"fixture-app\" exceeded progress deadline\n");
        builder.append("hudson.AbortException: script returned exit code 1\n");
        return builder.toString();
    }

    private static int indexOfLine(List<String> lines, String fragment) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    private static void run(Path executable, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "keytool fehlgeschlagen: " + output);
    }

    private JenkinsHttpReader reader(OpsConfig config) {
        return new JenkinsHttpReader(config, "apps-deploy", CLOCK, MAPPER);
    }

    private Path writeToken(String content) {
        try {
            Path file = Files.createTempFile(tempDir, "jenkins-token", ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static CollectionBudget budget(int maxRequests, long maxTotalInputBytes) {
        return new CollectionBudget(Duration.ofSeconds(90), Instant.parse("2026-10-01T10:05:00Z"), maxRequests, 8, 500,
                maxTotalInputBytes, 32 * 1024);
    }

    private static OpsConfig config(String baseUrl, Path tokenFile, String username, String proxyMode,
            String proxyHost, Integer proxyPort, Path additionalCaFile, int requestTimeoutSeconds,
            long maxBuildLogBytes) {
        return fullConfig(baseUrl, tokenFile, null, username, proxyMode, proxyHost, proxyPort, additionalCaFile,
                requestTimeoutSeconds, maxBuildLogBytes, List.of(), List.of("NAMESPACE", "IMAGE_TAG"));
    }

    private static OpsConfig fullConfig(String baseUrl, Path tokenFile, String tokenEnvVar, String username,
            String proxyMode, String proxyHost, Integer proxyPort, Path additionalCaFile, int requestTimeoutSeconds,
            long maxBuildLogBytes, List<List<String>> allowedJobPaths, List<String> allowedParameterNames) {
        Limits limits = new Limits(90, requestTimeoutSeconds, 5, 3, 60, 8, 500, 12, maxBuildLogBytes, 1024 * 1024,
                32L * 1024 * 1024, 32 * 1024, 256 * 1024);
        JenkinsDefinition jenkins = new JenkinsDefinition("apps-deploy", baseUrl, username,
                new JenkinsDefinition.SecretRef(tokenFile, tokenEnvVar),
                new JenkinsDefinition.TlsSettings(additionalCaFile),
                new JenkinsDefinition.ProxySettings(proxyMode, proxyHost, proxyPort, null, null),
                allowedJobPaths, allowedParameterNames);
        return new OpsConfig(1, DataMode.FIXTURE, Path.of("config/fixture.test.yaml"),
                Path.of("build/test-storage"), new SecuritySettings(false,
                        SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY, SecuritySettings.IDENTITY_RESTRICTED),
                limits, Map.of(), Map.of("apps-deploy", jenkins), Map.of(), Map.of(), null);
    }

    private static ResolvedTarget target(OpsConfig config, List<String> jobPath, int buildNumber,
            CollectionBudget budget) {
        return target(config, "apps-deploy", jobPath, buildNumber, budget);
    }

    private static ResolvedTarget target(OpsConfig config, String instanceId, List<String> jobPath, int buildNumber,
            CollectionBudget budget) {
        EnvironmentDefinition definition = new EnvironmentDefinition("ocp", "fixture-namespace", null,
                new DeploymentDefinition(instanceId, jobPath), null);
        return new ResolvedTarget("T-0001", config, DataMode.FIXTURE, "fixture-app", "integration", definition,
                new BuildRef(instanceId, jobPath, buildNumber), null, Instant.parse("2026-10-01T10:00:00Z"),
                Instant.parse("2026-10-01T10:35:00Z"), budget, List.of(), "fixture-scenario");
    }
}
