package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.jenkins.BuildParameter;
import ch.so.agi.ops.domain.jenkins.BuildRef;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.RepositoryDescriptor;
import ch.so.agi.ops.security.Redactor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Parser fuer die Jenkins-JSON-Remote-API. Der Parser setzt keine Blue-Ocean-, Pipeline-REST-
 * oder Timestamper-Plugins voraus: optionale Felder verbessern das Ergebnis, fehlen sie, bleibt
 * das Feld leer und wird als Hinweis notiert. Es werden niemals Werte erfunden.
 *
 * <p>Alle ausgegebenen Texte laufen durch {@link Redactor#redact(String)}. Werte nicht
 * erlaubter Parameter werden nicht einmal gelesen; von ihnen bleibt nur der Name in
 * {@code parameterNamesSeen}.</p>
 */
public final class JenkinsJsonParser {

    /** Obergrenze fuer einen einzelnen uebernommenen Parameterwert. */
    public static final int MAX_PARAMETER_VALUE_LENGTH = 4096;

    /** Millisekundenzeitstempel unterhalb dieser Grenze sind verdaechtig (eher Sekunden). */
    static final long PLAUSIBLE_MILLIS_LOWER_BOUND = 100_000_000_000L;

    private static final Pattern SHA_PATTERN = Pattern.compile("[0-9a-fA-F]{7,64}");

    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile(
            "^[a-z0-9][a-z0-9.\\-]*(?::[0-9]{1,5})?/[a-z0-9._\\-/]+(?::[A-Za-z0-9._\\-]+|@sha256:[0-9a-f]{64})$");

    private final JsonMapper mapper;

    public JenkinsJsonParser(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Wertet Buildmetadaten aus. Fehlende oder strukturfremde Felder fuehren zu
     * {@code metadataAvailable=false}, {@code timestampAvailable=false} und einer Meldung in
     * {@code notes} - nicht zu einem Absturz und nicht zu erfundenen Werten.
     *
     * @param json                  Rohantwort der Build-API
     * @param ref                   Buildreferenz, zu der die Antwort gehoert
     * @param allowedParameterNames erlaubte Parameternamen; nur diese Werte werden uebernommen
     */
    public BuildSnapshot parseBuildMetadata(String json, BuildRef ref, List<String> allowedParameterNames) {
        Objects.requireNonNull(ref, "ref");
        List<String> allowedNames = allowedParameterNames == null ? List.of() : List.copyOf(allowedParameterNames);
        JsonNode root = readObject(json);
        List<String> notes = new ArrayList<>();

        String displayName = redacted(text(root.get("displayName")));
        Boolean building = bool(root.get("building"));
        String result = redacted(text(root.get("result")));
        Long duration = number(root.get("duration"));
        Long estimatedDuration = number(root.get("estimatedDuration"));

        boolean metadataAvailable = root.has("displayName") || root.has("building") || root.has("result")
                || root.has("timestamp");
        if (!metadataAvailable) {
            notes.add("Die Jenkins-Antwort enthaelt keine erkennbaren Buildfelder "
                    + "(displayName/building/result); die Metadaten bleiben unvollstaendig.");
        }
        if (displayName == null) {
            notes.add("Feld displayName fehlt oder ist leer.");
        }
        if (building == null && result == null) {
            notes.add("Weder building noch result sind in der Antwort enthalten.");
        }
        if (duration == null) {
            notes.add("Feld duration fehlt oder ist ungueltig; die Builddauer bleibt unbekannt.");
        }
        if (estimatedDuration == null) {
            notes.add("Feld estimatedDuration fehlt oder ist ungueltig.");
        }

        Instant startedAt = null;
        boolean timestampAvailable = false;
        Long millis = number(root.get("timestamp"));
        if (millis != null && millis > 0) {
            if (millis < PLAUSIBLE_MILLIS_LOWER_BOUND) {
                notes.add("Der Zeitstempel der Jenkins-Antwort ist nicht als Millisekundenwert plausibel; "
                        + "die Startzeit bleibt unbekannt.");
            } else {
                startedAt = Instant.ofEpochMilli(millis);
                timestampAvailable = true;
            }
        } else {
            notes.add("Die Jenkins-Antwort enthaelt keinen verwertbaren Zeitstempel (timestamp); "
                    + "die Startzeit bleibt unbekannt.");
        }

        ParameterResult parameters = parameters(root, allowedNames, notes);
        List<RepositoryDescriptor> repositories = scmRepositories(root);
        if (repositories.isEmpty()) {
            notes.add("Es liegen keine strukturierten SCM-Angaben (hudson.plugins.git.*) zum Build vor.");
        }

        return new BuildSnapshot(
                ref,
                displayName,
                building,
                result,
                startedAt,
                duration,
                estimatedDuration,
                timestampAvailable,
                parameters.adopted(),
                parameters.namesSeen(),
                repositories,
                imageReferences(parameters.adopted()),
                namespaceHint(parameters.adopted()),
                metadataAvailable,
                List.copyOf(notes));
    }

    /**
     * Liest {@code lastFailed} aus dem Job-JSON. {@code lastFailed} wird ausdruecklich nicht
     * mit {@code lastUnsuccessful} gleichgesetzt: abgebrochene Builds sind keine Fehler.
     * Fehlt das Feld, wird {@code NOT_FOUND} gemeldet ("kein fehlgeschlagener Build bekannt").
     */
    public BuildSelection parseLastFailed(String json, String instanceId, List<String> jobPath) {
        return parseLastSelection(json, instanceId, jobPath, "lastFailed");
    }

    /** Liest {@code lastUnsuccessful}; dieses Feld kann auch abgebrochene Builds enthalten. */
    public BuildSelection parseLastUnsuccessful(String json, String instanceId, List<String> jobPath) {
        return parseLastSelection(json, instanceId, jobPath, "lastUnsuccessful");
    }

    /**
     * Liest strukturierte SCM-Repositories aus {@code actions}. Mehrere Repositories bleiben
     * getrennt. Es wird ausschliesslich strukturierten Feldern vertraut; ein beliebiger SHA aus
     * einem Console-Log ist keine Revision.
     */
    public List<RepositoryDescriptor> parseScmRepositories(String json) {
        return scmRepositories(readObject(json));
    }

    /**
     * true, wenn der Antwortkoerper offensichtlich eine HTML-Anmeldeseite statt JSON ist. Der
     * Koerper muss dafuer mit {@code <} beginnen; ein JSON-Dokument, das irgendwo die Zeichenfolge
     * {@code <html} enthaelt, gilt nicht als Login-HTML.
     */
    public static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String head = body.stripLeading();
        if (head.isEmpty() || head.charAt(0) != '<') {
            return false;
        }
        String probe = head.length() > 512 ? head.substring(0, 512) : head;
        String lower = probe.toLowerCase(Locale.ROOT);
        return lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.contains("<html");
    }

    private BuildSelection parseLastSelection(String json, String instanceId, List<String> jobPath, String field) {
        JsonNode root = readObject(json);
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            String label = "lastFailed".equals(field)
                    ? "kein fehlgeschlagener Build bekannt"
                    : "kein nicht erfolgreicher Build bekannt";
            throw new JenkinsParseException(ErrorCode.NOT_FOUND,
                    "Jenkins meldet zu diesem Job: " + label + " (Feld " + field + " fehlt).");
        }
        if (!node.isObject()) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Feld " + field + " ist kein Objekt und kann nicht ausgewertet werden.");
        }
        Long number = number(node.get("number"));
        if (number == null || number <= 0) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Feld " + field + " enthaelt keine gueltige Buildnummer.");
        }
        String result = redacted(text(node.get("result")));
        Boolean building = bool(node.get("building"));
        String reason = "lastFailed".equals(field)
                ? "Jenkins lastFailed wurde genau einmal zu einer konkreten Nummer aufgeloest; "
                        + "lastUnsuccessful wurde nicht gleichgesetzt."
                : "Jenkins lastUnsuccessful wurde genau einmal zu einer konkreten Nummer aufgeloest; "
                        + "dieses Feld kann auch abgebrochene Builds enthalten.";
        return new BuildSelection(instanceId, jobPath == null ? List.of() : List.copyOf(jobPath),
                number.intValue(), result, building, redacted(reason), true);
    }

    private ParameterResult parameters(JsonNode root, List<String> allowedNames, List<String> notes) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        collectParameterNodes(root.get("parameters"), nodes);
        JsonNode actions = root.get("actions");
        if (actions != null && actions.isArray()) {
            for (JsonNode action : actions) {
                if (action != null && action.isObject()) {
                    collectParameterNodes(action.get("parameters"), nodes);
                }
            }
        }
        List<String> namesSeen = new ArrayList<>();
        List<BuildParameter> adopted = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            String name = entry.getKey();
            namesSeen.add(name);
            if (!allowedNames.contains(name)) {
                // Der Wert eines nicht erlaubten Parameters wird nicht gelesen.
                continue;
            }
            JsonNode valueNode = entry.getValue().get("value");
            String value = text(valueNode);
            if (value == null && valueNode != null && !valueNode.isNull()) {
                value = jsonText(valueNode);
            }
            String raw = value == null ? "" : value;
            if (raw.length() > MAX_PARAMETER_VALUE_LENGTH) {
                raw = raw.substring(0, MAX_PARAMETER_VALUE_LENGTH);
                notes.add("Der Wert des erlaubten Parameters " + name + " wurde auf "
                        + MAX_PARAMETER_VALUE_LENGTH + " Zeichen begrenzt.");
            }
            Redactor.Result redaction = Redactor.redact(raw);
            adopted.add(new BuildParameter(name, redaction.text(), redaction.changed()));
        }
        if (namesSeen.isEmpty()) {
            notes.add("Die Jenkins-Antwort enthaelt keine Parameterliste.");
        } else if (adopted.isEmpty()) {
            notes.add("Von " + namesSeen.size() + " gesehenen Parametern ist keiner freigegeben; "
                    + "die Werte werden nicht uebernommen.");
        }
        return new ParameterResult(List.copyOf(adopted), List.copyOf(namesSeen));
    }

    private static void collectParameterNodes(JsonNode node, Map<String, JsonNode> target) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode parameter : node) {
            if (parameter == null || !parameter.isObject()) {
                continue;
            }
            String name = text(parameter.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            target.putIfAbsent(name, parameter);
        }
    }

    private List<RepositoryDescriptor> scmRepositories(JsonNode root) {
        JsonNode actions = root.get("actions");
        if (actions == null || !actions.isArray()) {
            return List.of();
        }
        List<RepositoryDescriptor> result = new ArrayList<>();
        for (JsonNode action : actions) {
            if (action == null || !action.isObject()) {
                continue;
            }
            String actionClass = text(action.get("_class"));
            if (actionClass == null) {
                continue;
            }
            boolean gitAction = actionClass.startsWith("hudson.plugins.git.")
                    || actionClass.contains(".git.");
            boolean scmMetadata = actionClass.startsWith("jenkins.scm.api.metadata.");
            if (!gitAction && !scmMetadata) {
                continue;
            }
            List<String> urls = remoteUrls(action);
            String commit = commitOf(action);
            String branch = branchOf(action);
            if (urls.isEmpty()) {
                continue;
            }
            for (String url : urls) {
                addDescriptor(result, url, branch, commit);
            }
            JsonNode buildsByBranchName = action.get("buildsByBranchName");
            if (buildsByBranchName != null && buildsByBranchName.isObject()) {
                for (Map.Entry<String, JsonNode> entry : buildsByBranchName.properties()) {
                    JsonNode value = entry.getValue();
                    String entryBranch = value != null && value.isObject()
                            ? firstNonNull(text(value.get("branchName")), entry.getKey())
                            : entry.getKey();
                    String entryCommit = value != null && value.isObject() ? commitOf(value) : null;
                    for (String url : urls) {
                        addDescriptor(result, url, entryBranch, entryCommit);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addDescriptor(List<RepositoryDescriptor> target, String url, String branch, String commit) {
        RepositoryDescriptor descriptor = new RepositoryDescriptor(
                ch.so.agi.ops.security.Redactor.redact(url).text(),
                branch == null ? null : ch.so.agi.ops.security.Redactor.redact(stripRefPrefix(branch)).text(),
                commit);
        if (!target.contains(descriptor) && !descriptor.repositoryIdHint().isBlank()) {
            target.add(descriptor);
        }
    }

    private static List<String> remoteUrls(JsonNode action) {
        List<String> urls = new ArrayList<>();
        addUrls(action.get("remoteUrls"), urls);
        addUrls(action.get("remoteUrl"), urls);
        addUrls(action.get("url"), urls);
        addUrls(action.get("repositoryUrl"), urls);
        addUrls(action.get("objectUrl"), urls);
        return List.copyOf(urls);
    }

    private static void addUrls(JsonNode node, List<String> target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isString()) {
            String value = node.asString().trim();
            if (!value.isEmpty() && !target.contains(value)) {
                target.add(value);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                addUrls(element, target);
            }
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                addUrls(entry.getValue(), target);
            }
        }
    }

    private static String commitOf(JsonNode node) {
        for (String field : new String[] {"SHA1", "commit", "revision", "sha", "hash"}) {
            String candidate = text(node.get(field));
            if (candidate != null && SHA_PATTERN.matcher(candidate.trim()).matches()) {
                return candidate.trim().toLowerCase(Locale.ROOT);
            }
        }
        JsonNode lastBuiltRevision = node.get("lastBuiltRevision");
        if (lastBuiltRevision != null && lastBuiltRevision.isObject()) {
            String candidate = commitOf(lastBuiltRevision);
            if (candidate != null) {
                return candidate;
            }
            JsonNode branches = lastBuiltRevision.get("branch");
            if (branches != null && branches.isArray()) {
                for (JsonNode branch : branches) {
                    if (branch != null && branch.isObject()) {
                        String branchCommit = commitOf(branch);
                        if (branchCommit != null) {
                            return branchCommit;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String branchOf(JsonNode action) {
        JsonNode lastBuiltRevision = action.get("lastBuiltRevision");
        if (lastBuiltRevision == null || !lastBuiltRevision.isObject()) {
            return null;
        }
        JsonNode branches = lastBuiltRevision.get("branch");
        if (branches == null || !branches.isArray()) {
            return null;
        }
        for (JsonNode branch : branches) {
            if (branch != null && branch.isObject()) {
                String name = text(branch.get("name"));
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return null;
    }

    private static String stripRefPrefix(String branch) {
        String value = branch.trim();
        for (String prefix : new String[] {"refs/remotes/origin/", "refs/remotes/", "refs/heads/", "origin/"}) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    private static List<String> imageReferences(List<BuildParameter> parameters) {
        List<String> images = new ArrayList<>();
        for (BuildParameter parameter : parameters) {
            String name = parameter.name().toUpperCase(Locale.ROOT);
            String value = parameter.value();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (name.contains("IMAGE") || IMAGE_REFERENCE_PATTERN.matcher(value.trim()).matches()) {
                if (!images.contains(value)) {
                    images.add(value);
                }
            }
        }
        return List.copyOf(images);
    }

    private static String namespaceHint(List<BuildParameter> parameters) {
        for (BuildParameter parameter : parameters) {
            String name = parameter.name().toUpperCase(Locale.ROOT);
            String value = parameter.value();
            if (value != null && !value.isBlank() && name.contains("NAMESPACE")) {
                return value;
            }
        }
        return null;
    }

    private JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR, "Die Jenkins-Antwort ist leer.");
        }
        if (looksLikeHtml(json)) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Die Antwort ist Login-HTML statt Jenkins-JSON.");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Die Jenkins-Antwort ist kein gueltiges JSON.", ex);
        }
        if (root == null || root.isNull() || !root.isObject()) {
            throw new JenkinsParseException(ErrorCode.SOURCE_PARSE_ERROR,
                    "Die Jenkins-Antwort ist kein JSON-Objekt.");
        }
        return root;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asString();
        }
        return null;
    }

    private static String jsonText(JsonNode node) {
        try {
            return node.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    private static String redacted(String value) {
        return value == null ? null : Redactor.redact(value).text();
    }

    private static Boolean bool(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isString()) {
            String value = node.asString().trim();
            if ("true".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static Long number(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.valueOf(node.asString().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private record ParameterResult(List<BuildParameter> adopted, List<String> namesSeen) {
    }
}
