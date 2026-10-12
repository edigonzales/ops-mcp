package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.ScopeViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des URL-Baus. Die Beispiele verwenden die reservierte Domain {@code example.invalid};
 * es findet keine Namensaufloesung statt. {@code http} wird hier nur verwendet, weil der lokale
 * Testserver kein TLS anbietet; die Konfigurationsvalidierung verlangt fuer Live-Eintraege
 * weiterhin {@code https}.
 */
class JenkinsUrlBuilderTest {

    private static final String BASE = "https://jenkins.example.invalid";

    @Test
    void jobApiUrlUsesJobSegmentsAndTree() {
        String url = JenkinsUrlBuilder.jobApiUrl(BASE, List.of("folder", "app"), "number,result");

        assertEquals(BASE + "/job/folder/job/app/api/json?tree=number,result", url);
    }

    @Test
    void baseUrlTrailingSlashIsNormalized() {
        String url = JenkinsUrlBuilder.jobApiUrl(BASE + "/", List.of("app"), "number");

        assertEquals(BASE + "/job/app/api/json?tree=number", url);
    }

    @Test
    void buildApiUrlAndConsoleTextUrlUseConcreteNumber() {
        String api = JenkinsUrlBuilder.buildApiUrl(BASE, List.of("folder", "app"), 42, "displayName,building");
        String console = JenkinsUrlBuilder.consoleTextUrl(BASE, List.of("folder", "app"), 42);

        assertEquals(BASE + "/job/folder/job/app/42/api/json?tree=displayName,building", api);
        assertEquals(BASE + "/job/folder/job/app/42/consoleText", console);
    }

    @Test
    void lastFailedUrlIsTheJobApi() {
        String lastFailed = JenkinsUrlBuilder.lastFailedApiUrl(BASE, List.of("app"),
                JenkinsHttpReader.LAST_FAILED_TREE);

        assertEquals(BASE + "/job/app/api/json?tree=" + JenkinsHttpReader.LAST_FAILED_TREE, lastFailed);
        assertTrue(lastFailed.contains("lastFailed"));
        assertTrue(!lastFailed.contains("lastUnsuccessful"));
    }

    @Test
    void everySegmentIsEncodedIndividually() {
        assertEquals("my%20job", JenkinsUrlBuilder.encodeSegment("my job"));
        assertEquals("%C3%A4", JenkinsUrlBuilder.encodeSegment("\u00e4"));
        assertEquals("a%2Bb", JenkinsUrlBuilder.encodeSegment("a+b"));
        assertEquals("a%23b", JenkinsUrlBuilder.encodeSegment("a#b"));
        assertEquals("a%3Fb", JenkinsUrlBuilder.encodeSegment("a?b"));
        assertEquals("plain", JenkinsUrlBuilder.encodeSegment("plain"));

        String url = JenkinsUrlBuilder.jobApiUrl(BASE, List.of("my folder", "my job"), "number");
        assertEquals(BASE + "/job/my%20folder/job/my%20job/api/json?tree=number", url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "  ", "/", "..", "../evil", "a/b", "a\\b", "\\", "a\u0000b", "a\nb",
            " job", "job ", "http://evil.example.invalid"})
    void forbiddenSegmentsAreRejected(String segment) {
        ScopeViolationException exception = assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.encodeSegment(segment));

        assertEquals(ErrorCode.SCOPE_DENIED, exception.code());
    }

    @Test
    void jobPathMustNotBeEmpty() {
        ScopeViolationException exception = assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.jobApiUrl(BASE, List.of(), "number"));

        assertEquals(ErrorCode.SCOPE_DENIED, exception.code());
    }

    @Test
    void buildNumberMustBePositive() {
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.buildApiUrl(BASE, List.of("app"), 0, "number"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.consoleTextUrl(BASE, List.of("app"), -1));
    }

    @Test
    void foreignHostIsRejected() {
        JenkinsUrlBuilder.requireCompatibleBase(BASE, BASE + "/job/app");

        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireCompatibleBase(BASE, "https://evil.example.invalid/job/app"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireCompatibleBase(BASE, "https://jenkins.example.invalid:8443/job/app"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireCompatibleBase(BASE, "http://jenkins.example.invalid/job/app"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireCompatibleBase(BASE, "https://jenkins.example.invalid.evil.invalid/job"));
    }

    @Test
    void baseUrlWithUserinfoQueryOrFragmentIsRejected() {
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireBase("https://user:secret@jenkins.example.invalid"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireBase("https://jenkins.example.invalid?x=1"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireBase("ftp://jenkins.example.invalid"));
        assertThrows(ScopeViolationException.class,
                () -> JenkinsUrlBuilder.requireBase("https://jenkins.example.invalid/a/../b"));
    }

    @Test
    void originIsSchemeHostAndPortWithoutCredentials() {
        assertEquals("https://jenkins.example.invalid", JenkinsUrlBuilder.origin(BASE + "/job/app"));
        assertEquals("http://127.0.0.1:8080", JenkinsUrlBuilder.origin("http://127.0.0.1:8080/job/app"));
        assertEquals("https://jenkins.example.invalid", JenkinsUrlBuilder.origin("https://jenkins.example.invalid:443/x"));
    }

    @Test
    void browserLinkIsResolvedAgainstTheInstanceAndAllowlist() {
        JenkinsUrlBuilder.ParsedBuildLink link = JenkinsUrlBuilder.parseBrowserLink(BASE,
                List.of(List.of("folder", "app")),
                BASE + "/job/folder/job/app/42/console");

        assertEquals(List.of("folder", "app"), link.jobPath());
        assertEquals(42, link.buildNumber());

        JenkinsUrlBuilder.ParsedBuildLink encoded = JenkinsUrlBuilder.parseBrowserLink(BASE, List.of(),
                BASE + "/job/folder/job/my%20app/7/");
        assertEquals(List.of("folder", "my app"), encoded.jobPath());
        assertEquals(7, encoded.buildNumber());
    }

    @Test
    void browserLinkOutsideAllowlistOrForeignOriginIsRejected() {
        assertThrows(ScopeViolationException.class, () -> JenkinsUrlBuilder.parseBrowserLink(BASE,
                List.of(List.of("other", "app")), BASE + "/job/folder/job/app/42/"));
        assertThrows(ScopeViolationException.class, () -> JenkinsUrlBuilder.parseBrowserLink(BASE, List.of(),
                "https://evil.example.invalid/job/folder/job/app/42/"));
        assertThrows(ScopeViolationException.class, () -> JenkinsUrlBuilder.parseBrowserLink(BASE, List.of(),
                "https://user:secret@jenkins.example.invalid/job/app/42/"));
        assertThrows(ScopeViolationException.class, () -> JenkinsUrlBuilder.parseBrowserLink(BASE, List.of(),
                BASE + "/job/app/"));
        assertThrows(ScopeViolationException.class, () -> JenkinsUrlBuilder.parseBrowserLink(BASE, List.of(),
                BASE + "/job/../app/42/"));
    }
}
