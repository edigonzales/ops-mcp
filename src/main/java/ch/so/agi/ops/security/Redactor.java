package ch.so.agi.ops.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministische Redaktion von Zugangsdaten in Texten, bevor sie gespeichert, ueber ein
 * Tool ausgegeben oder in einen Bericht uebernommen werden.
 *
 * <p>Wichtig: Die Originalwerte werden <em>nicht</em> in einer separaten Map aufbewahrt. Die
 * Redaktion ist best effort; Regex-Redaktion garantiert keine vollstaendige Anonymisierung.
 * Das ist im Manifest als Einschraenkung dokumentiert.</p>
 *
 * <p>Die Regelversion fliesst in das Manifest ein, damit spaeter erkennbar ist, mit welchen
 * Regeln ein Snapshot bereinigt wurde.</p>
 */
public final class Redactor {

    /** Regelversion; bei jeder Aenderung der Regeln erhoehen. */
    public static final String RULE_VERSION = "redaction-1";

    /** Ab dieser Wertlaenge werden Zuweisungen an verdaechtige Schluessel redigiert. */
    public static final int SUSPICIOUS_VALUE_MIN_LENGTH = 12;

    public static final String MASK = "[redigiert]";

    private static final List<Rule> RULES = List.of(
            // Authorization-Werte werden bis zum Zeilenende maskiert. Das deckt auch die
            // Digest-Form mit Parametern ab: Authorization: Digest username="x", response="..."
            new Rule("authorization-header",
                    Pattern.compile("(?i)\\b(authorization\\s*[:=]\\s*)([^\\r\\n]{8,})"),
                    matcher -> matcher.group(1) + MASK),
            new Rule("proxy-authorization-header",
                    Pattern.compile("(?i)\\b(proxy-authorization\\s*[:=]\\s*)([^\\r\\n]{8,})"),
                    matcher -> matcher.group(1) + MASK),
            new Rule("cookie-header",
                    Pattern.compile("(?i)\\b(set-cookie|cookie)\\s*:\\s*[^\\r\\n]+"),
                    matcher -> matcher.group(1) + ": " + MASK),
            new Rule("private-key-block",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                    matcher -> MASK),
            new Rule("private-key-marker",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    matcher -> MASK),
            new Rule("jwt",
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}"),
                    matcher -> MASK),
            new Rule("aws-access-key-id",
                    Pattern.compile("\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA)[A-Z0-9]{16}\\b"),
                    matcher -> MASK),
            new Rule("gcp-api-key",
                    Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
                    matcher -> MASK),
            new Rule("github-token",
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
                    matcher -> MASK),
            new Rule("slack-token",
                    Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),
                    matcher -> MASK),
            new Rule("url-userinfo",
                    Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)([^/@\\s:]+)(:[^/@\\s]+)?@"),
                    matcher -> matcher.group(1) + MASK + "@"),
            new Rule("signed-url-parameter",
                    Pattern.compile("(?i)([?&;](?:x-amz-signature|x-amz-credential|x-amz-security-token|sig|signature|access_token|token|apikey|api_key|key|auth|sas|se|sp|sv)=)([^&\\s\"']+)"),
                    matcher -> matcher.group(1) + MASK),
            new Rule("bearer-literal",
                    Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/=]{16,}"),
                    matcher -> "Bearer " + MASK),
            new Rule("suspicious-assignment",
                    // Deutsche Schluesselwoerter (Passwort, Kennwort) sind ausdruecklich
                    // enthalten: Log- und Berichtszeilen in Betriebssystemen sind oft deutsch.
                    Pattern.compile("(?i)\\b([A-Za-z0-9_.\\-]*(?:pass(?:wor(?:d|t)|wd|phrase)?|kennwort|secret|token|"
                            + "apikey|api_key|access[_-]?key|client[_-]?secret|private[_-]?key|credential|auth))"
                            + "\\s*[\"']?\\s*[:=]\\s*[\"']?([^\\s\"',;]{12,})"),
                    matcher -> matcher.group(1) + "=" + MASK),
            new Rule("suspicious-cli-flag",
                    Pattern.compile("(?i)(--(?:password|passwort|kennwort|token|secret|api-?key|client-secret)[= ])"
                            + "([^\\s\"']{6,})"),
                    matcher -> matcher.group(1) + MASK),
            new Rule("long-owned-hex-secret",
                    Pattern.compile("\\b[0-9a-fA-F]{48,}\\b"),
                    matcher -> MASK));

    private Redactor() {
    }

    /** Ergebnis einer Redaktion inklusive Zaehler je Regel. */
    public record Result(String text, Map<String, Integer> counts, int totalCount, boolean changed) {

        public Result {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }

        public static Result unchanged(String text) {
            return new Result(text, Map.of(), 0, false);
        }

        public Result merge(Result other) {
            Map<String, Integer> merged = new LinkedHashMap<>(counts);
            other.counts.forEach((key, value) -> merged.merge(key, value, Integer::sum));
            return new Result(other.text, merged, totalCount + other.totalCount, changed || other.changed);
        }
    }

    /** Redigiert einen einzelnen Text. */
    public static Result redact(String input) {
        if (input == null || input.isEmpty()) {
            return Result.unchanged(input == null ? "" : input);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        String current = input;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(current);
            StringBuilder sb = new StringBuilder();
            int replaced = 0;
            while (matcher.find()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(rule.replacement().apply(matcher)));
                replaced++;
            }
            if (replaced > 0) {
                matcher.appendTail(sb);
                current = sb.toString();
                counts.merge(rule.name(), replaced, Integer::sum);
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new Result(current, counts, total, total > 0);
    }

    /** Redigiert jede Zeile einzeln und behaelt die Zeilenstruktur bei. */
    public static Result redactLines(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> result = new java.util.ArrayList<>(lines.size());
        int total = 0;
        for (String line : lines) {
            Result lineResult = redact(line);
            result.add(lineResult.text());
            lineResult.counts().forEach((key, value) -> counts.merge(key, value, Integer::sum));
            total += lineResult.totalCount();
        }
        return new Result(String.join("\n", result), counts, total, total > 0);
    }

    /** Fuehrt Zaehler aus mehreren Redaktionen zusammen. */
    public static Map<String, Integer> mergeCounts(List<Map<String, Integer>> countMaps) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Map<String, Integer> counts : countMaps) {
            counts.forEach((key, value) -> merged.merge(key, value, Integer::sum));
        }
        return merged;
    }

    /**
     * Prueft, ob ein Text offensichtliche Secretmarker enthaelt. Wird fuer Negativtests und
     * als letzte Sicherung vor der Ausgabe verwendet.
     */
    public static boolean containsLikelySecret(String text) {
        if (text == null) {
            return false;
        }
        return RULES.stream().anyMatch(rule -> rule.pattern().matcher(text).find());
    }

    private record Rule(String name, Pattern pattern, java.util.function.Function<Matcher, String> replacement) {

        /** Regel, die den Fund vollstaendig durch die Maske ersetzt. */
        static Rule masking(String name, String regex) {
            return new Rule(name, Pattern.compile(regex), matcher -> MASK);
        }

        /** Regel, die den Fund durch die erste Gruppe plus Maske ersetzt. */
        static Rule keepPrefix(String name, String regex) {
            return new Rule(name, Pattern.compile(regex), Matcher::group);
        }
    }
}
