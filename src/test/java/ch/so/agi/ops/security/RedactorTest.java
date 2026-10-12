package ch.so.agi.ops.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redaktionstests. Sie belegen, dass Canary-Tokens in Logs, Headerzeilen, Fehlertexten und
 * Berichtsentwuerfen nicht im bereinigten Ergebnis verbleiben.
 *
 * <p>Die Regeln sind best effort; die Tests pruefen deshalb die dokumentierten Muster und
 * nicht eine vollstaendige Anonymisierung.</p>
 */
class RedactorTest {

    /** Einheitlicher Canary-Wert; darf in keinem Ausgabetext vorkommen. */
    private static final String CANARY = "T0pS3cr3tC4n4ryV4lu3";

    private static void assertNoCanary(String text, String description) {
        assertFalse(text.contains(CANARY), description + ": Canary-Wert ist im Klartext erhalten: " + text);
    }

    @Test
    @DisplayName("Authorization-Header wird auch ohne Bearer-Literal maskiert")
    void authorizationHeaderWithoutScheme() {
        String[] inputs = {
                "authorization=abcdefghijklmnop",
                "Authorization: token " + CANARY,
                "authorization: " + CANARY,
                "Proxy-Authorization: Basic " + CANARY,
                "x-proxy-authorization: " + CANARY,
                "Authorization: Digest username=\"x\", response=\"" + CANARY + "\""
        };
        for (String input : inputs) {
            Redactor.Result result = Redactor.redact(input);
            assertNoCanary(result.text(), input);
            assertTrue(result.totalCount() >= 1, "Regel muss greifen: " + input);
            assertTrue(result.text().contains(Redactor.MASK), "Maske fehlt: " + result.text());
        }
    }

    @Test
    @DisplayName("Bearer-Token, JWT und Cookies werden maskiert")
    void tokenForms() {
        List<String> inputs = List.of(
                "Authorization: Bearer " + CANARY,
                "Cookie: session=" + CANARY,
                "Set-Cookie: token=" + CANARY + "; Path=/",
                "curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij'",
                "X-Auth: " + CANARY);
        for (String input : inputs) {
            Redactor.Result result = Redactor.redact(input);
            assertTrue(result.totalCount() >= 1, "Regel muss greifen: " + input);
        }
    }

    @Test
    @DisplayName("Passwort-, Token- und Secret-Zuweisungen werden maskiert")
    void assignments() {
        List<String> inputs = List.of(
                "password=" + CANARY,
                "PASSWORD: " + CANARY,
                "db_password = " + CANARY,
                "api_key=" + CANARY,
                "API_TOKEN=" + CANARY,
                "client_secret: " + CANARY,
                "access_key=" + CANARY,
                "--password=" + CANARY,
                "export JENKINS_TOKEN=" + CANARY,
                "Passwort: " + CANARY,
                "Passwort=" + CANARY,
                "Kennwort=" + CANARY,
                "passwort=" + CANARY,
                "PASSWORT: " + CANARY,
                "--passwort=" + CANARY);
        for (String input : inputs) {
            Redactor.Result result = Redactor.redact(input);
            assertNoCanary(result.text(), input);
        }
    }

    @Test
    @DisplayName("Private-Key-Marker und signierte URL-Parameter werden maskiert")
    void keysAndSignedUrls() {
        String key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        Redactor.Result keyResult = Redactor.redact(key);
        assertFalse(keyResult.text().contains("MIIEowIBAAKCAQEA"), "Schluesselmaterial muss entfernt werden.");
        assertTrue(keyResult.text().contains(Redactor.MASK));

        String url = "https://storage.invalid/file?X-Amz-Signature=" + CANARY + "&X-Amz-Credential=abc";
        Redactor.Result urlResult = Redactor.redact(url);
        assertNoCanary(urlResult.text(), url);
    }

    @Test
    @DisplayName("Ein Commit-Hash von 40 Hex-Zeichen bleibt unveraendert")
    void commitHashIsPreserved() {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        Redactor.Result result = Redactor.redact("checkout commit " + commit);
        assertEquals("checkout commit " + commit, result.text(),
                "Ein 40-stelliger Git-Hash darf nicht als Secret redigiert werden.");
        assertEquals(0, result.totalCount());
    }

    @Test
    @DisplayName("Sehr lange Hex-Werte werden als verdaechtiges Secret maskiert")
    void longHexSecretIsMasked() {
        String longHex = "0123456789abcdef0123456789abcdef0123456789abcdef";
        Redactor.Result result = Redactor.redact("value=" + longHex);
        assertFalse(result.text().contains(longHex));
    }

    @Test
    @DisplayName("Zeilenweise Redaktion erhaelt die Zeilenanzahl")
    void lineRedactionKeepsStructure() {
        List<String> lines = List.of(
                "Zeile 1 ohne Secret",
                "Authorization: Bearer " + CANARY,
                "Zeile 3 ohne Secret",
                "password=" + CANARY);
        Redactor.Result result = Redactor.redactLines(lines);
        assertEquals(4, result.text().split("\n", -1).length, "Die Zeilenstruktur muss erhalten bleiben.");
        assertNoCanary(result.text(), "redactLines");
        assertTrue(result.totalCount() >= 2);
    }

    @Test
    @DisplayName("Erkennung von Secretmarkern fuer Negativtests")
    void detectionHelper() {
        assertTrue(Redactor.containsLikelySecret("Authorization: Basic " + CANARY));
        assertTrue(Redactor.containsLikelySecret("token=" + CANARY));
        assertFalse(Redactor.containsLikelySecret("Der Container wurde neu gestartet."));
        assertFalse(Redactor.containsLikelySecret("Probe-Pfad /healthz, Port 8080"));
    }

    @Test
    @DisplayName("Kuenstlich in Fehlerausgaben eingebaute Secrets werden entfernt")
    void secretsInsideErrorTexts() {
        String error = "java.io.IOException: connect failed for https://user:" + CANARY + "@jenkins.invalid/api "
                + "(token=" + CANARY + ")";
        Redactor.Result result = Redactor.redact(error);
        assertNoCanary(result.text(), "Fehlerausgabe");
    }

    @Test
    @DisplayName("Die Regelversion ist stabil und dokumentiert")
    void ruleVersionIsStable() {
        assertEquals("redaction-1", Redactor.RULE_VERSION);
        assertEquals(Redactor.RULE_VERSION, Redactor.RULE_VERSION);
    }
}
