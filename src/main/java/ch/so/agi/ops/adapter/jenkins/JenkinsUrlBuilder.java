package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.ScopeViolationException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Baut Jenkins-URLs ausschliesslich aus der festen, konfigurierten Basisadresse und einem
 * Jobpfad aus einzelnen Segmenten. Es gibt bewusst keine Methode, die eine vollstaendige URL
 * aus einer Modelleingabe uebernimmt.
 *
 * <p>Regeln:</p>
 * <ul>
 *   <li>Jedes Jobpfadsegment wird einzeln mit {@link URLEncoder#encode(String, java.nio.charset.Charset)}
 *       kodiert; das von {@code URLEncoder} erzeugte {@code +} wird zu {@code %20}.</li>
 *   <li>Segmente mit {@code /}, {@code \}, {@code ..}, Steuerzeichen, fuehrenden/abschliessenden
 *       Leerzeichen oder leerem Inhalt werden abgewiesen ({@code SCOPE_DENIED}).</li>
 *   <li>Eine Basisadresse mit Userinfo, Query, Fragment oder fremdem Host wird abgewiesen; ein
 *       Hostwechsel ist kein Erfolgsfall, sondern ein abgewiesener Angriff (S-02).</li>
 *   <li>{@code tree}-Ausdruecke sind feste, begrenzte Konstanten des Adapters und werden gegen
 *       einen konservativen Zeichensatz geprueft.</li>
 * </ul>
 */
public final class JenkinsUrlBuilder {

    /** Maximale Laenge eines einzelnen Jobpfadsegments. */
    public static final int MAX_SEGMENT_LENGTH = 200;

    /** Maximale Anzahl von Jobpfadsegmenten (Folder-Tiefe). */
    public static final int MAX_JOB_PATH_SEGMENTS = 12;

    /** Maximale Laenge einer erzeugten URL. */
    public static final int MAX_URL_LENGTH = 2048;

    private JenkinsUrlBuilder() {
    }

    /**
     * URL der Job-Metadaten: {@code <base>/job/<seg1>/job/<seg2>/api/json?tree=<tree>}.
     * Wird unter anderem fuer {@code lastFailed} verwendet.
     */
    public static String jobApiUrl(String baseUrl, List<String> jobPath, String tree) {
        String base = requireBase(baseUrl);
        String path = jobSegments(jobPath);
        return base + path + "/api/json?tree=" + requireTree(tree);
    }

    /** URL der Build-Metadaten: {@code <base>/job/.../<buildNumber>/api/json?tree=<tree>}. */
    public static String buildApiUrl(String baseUrl, List<String> jobPath, int buildNumber, String tree) {
        String base = requireBase(baseUrl);
        String path = jobSegments(jobPath);
        return base + path + "/" + requireBuildNumber(buildNumber) + "/api/json?tree=" + requireTree(tree);
    }

    /** URL des Console-Texts: {@code <base>/job/.../<buildNumber>/consoleText}. */
    public static String consoleTextUrl(String baseUrl, List<String> jobPath, int buildNumber) {
        String base = requireBase(baseUrl);
        String path = jobSegments(jobPath);
        return base + path + "/" + requireBuildNumber(buildNumber) + "/consoleText";
    }

    /**
     * URL der gezielten Abfrage des letzten fehlgeschlagenen Builds. {@code lastFailed} ist
     * nicht {@code lastUnsuccessful}: abgebrochene Builds sind keine Fehler.
     */
    public static String lastFailedApiUrl(String baseUrl, List<String> jobPath, String tree) {
        return jobApiUrl(baseUrl, jobPath, tree);
    }

    /** URL der gezielten Abfrage des letzten nicht erfolgreichen Builds (kann ABORTED enthalten). */
    public static String lastUnsuccessfulApiUrl(String baseUrl, List<String> jobPath, String tree) {
        return jobApiUrl(baseUrl, jobPath, tree);
    }

    /** Kodiert ein einzelnes Jobpfadsegment; nicht erlaubte Segmente werden abgewiesen. */
    public static String encodeSegment(String segment) {
        String problem = segmentProblem(segment);
        if (problem != null) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Unzulaessiges Jobpfadsegment (" + problem + "): " + ch.so.agi.ops.security.PathSafety.describe(segment));
        }
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Prueft, ob eine Basisadresse zur konfigurierten Instanz passt. Ein abweichender Host,
     * ein anderes Schema oder ein anderer Port werden abgewiesen (kein Hostwechsel).
     */
    public static void requireCompatibleBase(String configuredBaseUrl, String candidateBaseUrl) {
        URI configured = parse(configuredBaseUrl, "konfigurierte Basisadresse");
        URI candidate = parse(candidateBaseUrl, "zu pruefende Adresse");
        String configuredOrigin = originOf(configured);
        String candidateOrigin = originOf(candidate);
        if (!configuredOrigin.equals(candidateOrigin)) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Adresse gehoert nicht zur konfigurierten Jenkins-Instanz (kein Hostwechsel): "
                            + candidateOrigin);
        }
    }

    /** Herkunft (Schema, Host, Port) einer absoluten HTTP(S)-Adresse; ohne Pfad und ohne Zugangsdaten. */
    public static String origin(String url) {
        return originOf(parse(url, "Adresse"));
    }

    /**
     * Loest einen Browserlink auf einen Build gegen die konfigurierte Instanz und die Allowlist
     * auf. Der Link darf nur denselben Host betreffen und muss einen erlaubten Jobpfad sowie
     * eine konkrete Buildnummer enthalten.
     *
     * @param configuredBaseUrl feste Basisadresse der Instanz
     * @param allowedJobPaths   obere Grenze erlaubter Jobpfade; eine leere Liste bedeutet "keine
     *                          zusaetzliche Einschraenkung" wie in der Konfigurationsvalidierung
     * @param candidateUrl      zu pruefender Browserlink
     */
    public static ParsedBuildLink parseBrowserLink(String configuredBaseUrl, List<List<String>> allowedJobPaths,
            String candidateUrl) {
        if (candidateUrl == null || candidateUrl.isBlank()) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Leerer Buildlink.");
        }
        requireCompatibleBase(configuredBaseUrl, candidateUrl);
        URI uri = parse(candidateUrl, "Buildlink");
        if (uri.getUserInfo() != null) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Buildlink enthaelt Zugangsdaten (Userinfo) und wird abgewiesen.");
        }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        List<String> rawSegments = new ArrayList<>();
        for (String segment : rawPath.split("/")) {
            if (!segment.isEmpty()) {
                rawSegments.add(segment);
            }
        }
        List<String> jobPath = new ArrayList<>();
        Integer buildNumber = null;
        for (int i = 0; i < rawSegments.size(); i++) {
            String segment = rawSegments.get(i);
            if ("job".equals(segment) && i + 1 < rawSegments.size()) {
                if (buildNumber != null) {
                    break;
                }
                jobPath.add(decodeSegment(rawSegments.get(++i)));
                continue;
            }
            if (isAllDigits(segment)) {
                buildNumber = Integer.valueOf(segment);
                break;
            }
            // Weitere Segmente (z. B. console, api, json) sind fuer die Aufloesung unerheblich.
        }
        if (jobPath.isEmpty() || buildNumber == null) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Der Buildlink nennt keinen vollstaendigen /job/.../<nummer>-Pfad.");
        }
        for (String segment : jobPath) {
            String problem = segmentProblem(segment);
            if (problem != null) {
                throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                        "Der Buildlink enthaelt ein unzulaessiges Jobsegment (" + problem + ").");
            }
        }
        if (!allowedJobPaths.isEmpty() && !allowedJobPaths.contains(List.copyOf(jobPath))) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Der Jobpfad des Buildlinks steht nicht in der konfigurierten Allowlist.");
        }
        if (buildNumber <= 0) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Der Buildlink nennt keine positive Buildnummer.");
        }
        return new ParsedBuildLink(List.copyOf(jobPath), buildNumber);
    }

    /** Aufgeloester Browserlink: Jobpfad und konkrete Buildnummer. */
    public record ParsedBuildLink(List<String> jobPath, int buildNumber) {
    }

    /** Normalisierte Basisadresse ohne abschliessenden Schraegstrich. */
    public static String requireBase(String baseUrl) {
        URI uri = parse(baseUrl, "Jenkins-Basisadresse");
        if (uri.getUserInfo() != null) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Die Jenkins-Basisadresse enthaelt Zugangsdaten (Userinfo) und wird abgewiesen.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Die Jenkins-Basisadresse darf keine Query und kein Fragment enthalten.");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.contains("..") || path.contains("//") || path.contains("\\")) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Die Jenkins-Basisadresse enthaelt einen unzulaessigen Pfad.");
        }
        String base = originOf(uri) + path;
        if (base.length() > MAX_URL_LENGTH) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Die Jenkins-Basisadresse ist zu lang.");
        }
        return base;
    }

    private static String jobSegments(List<String> jobPath) {
        if (jobPath == null || jobPath.isEmpty()) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Es ist kein Jobpfad konfiguriert; ein Job wird nicht geraten.");
        }
        if (jobPath.size() > MAX_JOB_PATH_SEGMENTS) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Der Jobpfad hat mehr als " + MAX_JOB_PATH_SEGMENTS + " Segmente.");
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : jobPath) {
            sb.append("/job/").append(encodeSegment(segment));
        }
        if (sb.length() > MAX_URL_LENGTH) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Der Jobpfad ist zu lang.");
        }
        return sb.toString();
    }

    private static int requireBuildNumber(int buildNumber) {
        if (buildNumber <= 0) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Buildnummer muss positiv sein: " + buildNumber);
        }
        return buildNumber;
    }

    private static String requireTree(String tree) {
        if (tree == null || tree.isBlank()) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Leerer tree-Ausdruck.");
        }
        if (tree.length() > 1024) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Der tree-Ausdruck ist zu lang.");
        }
        for (int i = 0; i < tree.length(); i++) {
            char c = tree.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ',' || c == '[' || c == ']' || c == '*';
            if (!allowed) {
                throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                        "Der tree-Ausdruck enthaelt ein unzulaessiges Zeichen.");
            }
        }
        return tree;
    }

    private static String segmentProblem(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "leer";
        }
        if (segment.length() > MAX_SEGMENT_LENGTH) {
            return "zu lang";
        }
        if (!segment.equals(segment.trim())) {
            return "fuehrende oder abschliessende Leerzeichen";
        }
        if (segment.contains("/") || segment.contains("\\")) {
            return "Pfadtrenner";
        }
        if (segment.contains("..")) {
            return "Pfadwechsel";
        }
        if (segment.contains("://")) {
            return "absolute URL";
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return "Steuerzeichen";
            }
        }
        return null;
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty() || value.length() > 12) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String decodeSegment(String rawSegment) {
        try {
            return java.net.URLDecoder.decode(rawSegment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Der Buildlink enthaelt ein nicht dekodierbares Jobsegment.");
        }
    }

    private static URI parse(String url, String what) {
        if (url == null || url.isBlank()) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Keine " + what + " angegeben.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Die " + what + " ist keine gueltige URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Die " + what + " muss http oder https verwenden.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED, "Die " + what + " hat keinen Hostnamen.");
        }
        return uri;
    }

    private static String originOf(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        boolean explicitPort = port > 0 && port != defaultPort;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (explicitPort ? ":" + port : "");
    }
}
