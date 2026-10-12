package ch.so.agi.ops.config;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.util.PathResolver;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strenger Zugriff auf einen YAML-Knoten: unbekannte Schluessel werden abgelehnt, Typen
 * werden validiert. Alle erkannten Probleme werden gesammelt, damit {@code doctor} und die
 * CLI sie gemeinsam ausgeben koennen.
 */
public final class StrictNode {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("schemaVersion", "mode", "storage", "security",
            "limits", "clusters", "jenkins", "repositories", "applications", "fixture");
    private static final Set<String> STORAGE_KEYS = Set.of("root");
    private static final Set<String> SECURITY_KEYS = Set.of("allowPersonalCredentials", "modelDataPolicy",
            "identityMode");
    private static final Set<String> LIMIT_KEYS = Set.of("collectTimeoutSeconds", "requestTimeoutSeconds",
            "connectTimeoutSeconds", "maxParallelRequests", "maxRequests", "maxPods", "maxEvents", "maxGitFiles",
            "maxBuildLogBytes", "maxContainerLogBytes", "maxTotalInputBytes", "maxEvidenceResponseBytes",
            "maxReportDraftBytes");
    private static final Set<String> CLUSTER_KEYS = Set.of("server", "ocExecutable", "kubeconfig", "context",
            "identityMode", "directHosts", "allowedNamespaces");
    private static final Set<String> JENKINS_KEYS = Set.of("baseUrl", "username", "token", "tls", "proxy",
            "allowedJobPaths", "allowedParameterNames");
    private static final Set<String> SECRET_KEYS = Set.of("file", "environmentVar");
    private static final Set<String> TLS_KEYS = Set.of("additionalCaFile");
    private static final Set<String> PROXY_KEYS = Set.of("mode", "host", "port", "username", "password");
    private static final Set<String> REPOSITORY_KEYS = Set.of("repositoryId", "localPath", "allowedFiles");
    private static final Set<String> APPLICATION_KEYS = Set.of("displayName", "environments");
    private static final Set<String> ENVIRONMENT_KEYS = Set.of("cluster", "namespace", "workload", "deployment",
            "configuration");
    private static final Set<String> WORKLOAD_KEYS = Set.of("kind", "name", "containerNames", "allWorkloadContainers");
    private static final Set<String> DEPLOYMENT_KEYS = Set.of("jenkins", "jobPath");
    private static final Set<String> CONFIGURATION_KEYS = Set.of("repository", "templateFiles", "parameterFiles",
            "allowedParameterNames", "revision");
    private static final Set<String> REVISION_KEYS = Set.of("source", "repositoryId", "commit", "forBuild");
    private static final Set<String> FIXTURE_KEYS = Set.of("root", "scenario", "clock", "fixedNow");

    private final JsonNode node;
    private final String path;
    private final Set<String> allowedKeys;
    private final List<OpsError> problems;

    private StrictNode(JsonNode node, String path, Set<String> allowedKeys, List<OpsError> problems) {
        this.node = node;
        this.path = path;
        this.allowedKeys = allowedKeys;
        this.problems = problems;
    }

    public static StrictNode root(JsonNode node) {
        StrictNode strict = new StrictNode(node, "", TOP_LEVEL_KEYS, new ArrayList<>());
        strict.rejectUnknownKeys(TOP_LEVEL_KEYS);
        return strict;
    }

    public boolean isObject() {
        return node != null && node.isObject();
    }

    /** Kindknoten mit eigenem Schluesselsatz. Fehlende Schluessel liefern {@code null}. */
    public StrictNode child(String name, List<OpsError> problemSink, boolean required) {
        if (!node.has(name) || node.get(name).isNull()) {
            if (required) {
                problemSink.add(problem(ErrorCode.CONFIG_INCOMPLETE, name, "Pflichtabschnitt fehlt: " + field(name)));
            }
            return null;
        }
        JsonNode child = node.get(name);
        if (!child.isObject()) {
            problemSink.add(problem(ErrorCode.CONFIG_INVALID, name, "Abschnitt muss ein Objekt sein: " + field(name)));
            return null;
        }
        Set<String> keys = keysFor(name);
        StrictNode strict = new StrictNode(child, field(name), keys, problemSink);
        if (!keys.isEmpty()) {
            // Nur bei einem bekannten Schluesselsatz pruefen. Die Schluessel selbst stammen
            // hier aus der Konfigurationsstruktur, nicht aus der Modelleingabe. Objekte mit
            // dynamischen Bezeichnern (clusters, jenkins, repositories, applications,
            // environments) werden ueber children() geprueft.
            strict.rejectUnknownKeys(keys);
        }
        return strict;
    }

    /** Alle Kindknoten als Map; nur fuer freie Zuordnungen wie Anwendungen, Cluster oder Jenkinsinstanzen. */
    public Map<String, StrictNode> children() {
        Map<String, StrictNode> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        java.util.Set<String> keys = keysForCollection(path);
        node.properties().forEach(entry -> {
            // Der Schluessel selbst ist ein dynamischer Bezeichner (Anwendung, Cluster,
            // Jenkins-Instanz, Umgebung) und wird hier bewusst nicht als unbekannt abgewiesen.
            // Geprueft wird der Inhalt dieses Eintrags gegen den passenden Schluesselsatz.
            StrictNode child = new StrictNode(entry.getValue(), field(entry.getKey()), keys, problems);
            child.rejectUnknownKeys(keys);
            result.put(entry.getKey(), child);
        });
        return result;
    }

    public String requireString(String name, List<OpsError> problemSink, String fallback) {
        if (!node.has(name) || node.get(name).isNull()) {
            problemSink.add(problem(ErrorCode.CONFIG_INCOMPLETE, name, "Pflichtfeld fehlt: " + field(name)));
            return fallback;
        }
        JsonNode value = node.get(name);
        if (!value.isString()) {
            problemSink.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss Text sein: " + field(name)));
            return fallback;
        }
        String text = value.asString();
        if (text.isBlank()) {
            problemSink.add(problem(ErrorCode.CONFIG_INCOMPLETE, name, "Feld darf nicht leer sein: " + field(name)));
            return fallback;
        }
        return text;
    }

    public String optionalString(String name, String fallback) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(name);
        if (!value.isString()) {
            problems.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss Text sein: " + field(name)));
            return fallback;
        }
        return value.asString();
    }

    public Integer optionalIntOrNull(String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return null;
        }
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber()) {
            problems.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss eine Ganzzahl sein: " + field(name)));
            return null;
        }
        return value.asInt();
    }

    public int requireInt(String name, List<OpsError> problemSink, int fallback) {
        if (!node.has(name) || node.get(name).isNull()) {
            problemSink.add(problem(ErrorCode.CONFIG_INCOMPLETE, name, "Pflichtfeld fehlt: " + field(name)));
            return fallback;
        }
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber()) {
            problemSink.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss eine Ganzzahl sein: " + field(name)));
            return fallback;
        }
        return value.asInt();
    }

    public int optionalInt(String name, int fallback) {
        Integer value = optionalIntOrNull(name);
        return value == null ? fallback : value;
    }

    public long optionalLong(String name, long fallback) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber()) {
            problems.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss eine Ganzzahl sein: " + field(name)));
            return fallback;
        }
        return value.asLong();
    }

    public boolean optionalBoolean(String name, boolean fallback) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(name);
        if (!value.isBoolean()) {
            problems.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss true oder false sein: " + field(name)));
            return fallback;
        }
        return value.asBoolean();
    }

    public List<String> stringList(String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return List.of();
        }
        JsonNode value = node.get(name);
        if (!value.isArray()) {
            problems.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss eine Liste sein: " + field(name)));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isString()) {
                problems.add(problem(ErrorCode.CONFIG_INVALID, name,
                        "Listeneintrag muss Text sein: " + field(name)));
                continue;
            }
            result.add(element.asString());
        }
        return List.copyOf(result);
    }

    /** Liste von Jobpfaden, jeder Jobpfad eine Liste von Segmenten. */
    public List<List<String>> jobPathList(String name, List<OpsError> problemSink, String pathForProblem) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return List.of();
        }
        JsonNode value = node.get(name);
        if (!value.isArray()) {
            problemSink.add(problem(ErrorCode.CONFIG_INVALID, name, "Feld muss eine Liste sein: " + field(name)));
            return List.of();
        }
        List<List<String>> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isArray()) {
                problemSink.add(problem(ErrorCode.CONFIG_INVALID, name,
                        "Jeder Jobpfad muss eine Liste von Segmenten sein: " + pathForProblem));
                continue;
            }
            List<String> segments = new ArrayList<>();
            boolean valid = true;
            for (JsonNode segment : element) {
                if (!segment.isString() || segment.asString().isBlank()) {
                    problemSink.add(problem(ErrorCode.CONFIG_INVALID, name,
                            "Jobpfadsegmente muessen nichtleere Zeichenfolgen sein: " + pathForProblem));
                    valid = false;
                    break;
                }
                String text = segment.asString();
                if (text.contains("/") || text.contains("..") || text.contains("\\")) {
                    problemSink.add(problem(ErrorCode.INVALID_INPUT, name,
                            "Jobpfadsegmente duerfen weder '/' noch '..' enthalten: " + pathForProblem
                                    + " (Segment: " + text + ")"));
                    valid = false;
                    break;
                }
                segments.add(text);
            }
            if (valid && !segments.isEmpty()) {
                result.add(List.copyOf(segments));
            }
        }
        return List.copyOf(result);
    }

    /** Pfadfeld mit Unterstuetzung fuer {@code ~}, {@code $HOME} und {@code ${HOME}}. */
    public Path resolvedPath(String name, Path baseDir, List<OpsError> problemSink, boolean required) {
        String raw = optionalString(name, null);
        if (raw == null || raw.isBlank()) {
            if (required) {
                problemSink.add(problem(ErrorCode.CONFIG_INCOMPLETE, name, "Pflichtfeld fehlt: " + field(name)));
            }
            return null;
        }
        try {
            return PathResolver.resolve(baseDir, raw, field(name));
        } catch (ConfigException ex) {
            problemSink.add(problem(ex.code(), name, ex.getMessage()));
            return null;
        }
    }

    private void rejectUnknownKeys(Set<String> keys) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            if (keys.contains(key) || ConfigLoader.isReservedExtensionKey(key)) {
                return;
            }
            problems.add(problem(ErrorCode.CONFIG_INVALID, key, "Unbekannter Konfigurationsschluessel: " + field(key)));
        });
    }

    private static Set<String> keysFor(String name) {
        if (!name.contains(".") && !name.contains("[")) {
            return switch (name) {
                case "storage" -> STORAGE_KEYS;
                case "security" -> SECURITY_KEYS;
                case "limits" -> LIMIT_KEYS;
                case "token" -> SECRET_KEYS;
                case "password" -> SECRET_KEYS;
                case "tls" -> TLS_KEYS;
                case "proxy" -> PROXY_KEYS;
                case "workload" -> WORKLOAD_KEYS;
                case "deployment" -> DEPLOYMENT_KEYS;
                case "configuration" -> CONFIGURATION_KEYS;
                case "revision" -> REVISION_KEYS;
                case "fixture" -> FIXTURE_KEYS;
                default -> Set.of();
            };
        }
        String tail = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return switch (tail) {
            case "storage" -> STORAGE_KEYS;
            case "security" -> SECURITY_KEYS;
            case "limits" -> LIMIT_KEYS;
            case "token" -> SECRET_KEYS;
            case "password" -> SECRET_KEYS;
            case "tls" -> TLS_KEYS;
            case "proxy" -> PROXY_KEYS;
            case "workload" -> WORKLOAD_KEYS;
            case "deployment" -> DEPLOYMENT_KEYS;
            case "configuration" -> CONFIGURATION_KEYS;
            case "revision" -> REVISION_KEYS;
            case "environments" -> APPLICATION_KEYS;
            case "fixture" -> FIXTURE_KEYS;
            default -> Set.of();
        };
    }

    /**
     * Schluesselsatz fuer dynamische Zuordnungen. Der Pfad wird von rechts ausgewertet, weil
     * die Zuordnungen verschachtelt sind (z. B. {@code applications.<id>.environments}).
     */
    static Set<String> keysForCollection(String collectionPath) {
        if (collectionPath == null || collectionPath.isEmpty()) {
            return Set.of();
        }
        String[] segments = collectionPath.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if ("clusters".equals(segment)) {
                return CLUSTER_KEYS;
            }
            if ("jenkins".equals(segment)) {
                return JENKINS_KEYS;
            }
            if ("repositories".equals(segment)) {
                return REPOSITORY_KEYS;
            }
            if ("applications".equals(segment)) {
                return APPLICATION_KEYS;
            }
            if ("environments".equals(segment)) {
                return ENVIRONMENT_KEYS;
            }
        }
        return Set.of();
    }

    private OpsError problem(ErrorCode code, String name, String message) {
        return new OpsError(code, field(name), message, false,
                "Die Konfiguration korrigieren und den Befehl erneut ausfuehren.");
    }

    private String field(String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    @Override
    public String toString() {
        return "StrictNode[" + path + "]";
    }
}
