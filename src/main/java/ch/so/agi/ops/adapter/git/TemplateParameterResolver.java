package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.git.ConfigValue;
import ch.so.agi.ops.domain.git.ContainerConfig;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import ch.so.agi.ops.security.Redactor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loest Workload-Templates und {@code .env}-aehnliche Parameterdateien auf. Die Verarbeitung
 * geschieht aus den <em>Inhaltsstrings</em>, damit auch Fixtureadapter dieselbe Logik
 * verwenden koennen.
 *
 * <p>V1 loest bewusst klein auf:</p>
 * <ul>
 *   <li>nur einfache, explizite Parameterzuweisungen {@code NAME=wert}, {@code NAME="wert"}
 *       oder {@code NAME='wert'}; Kommentarzeilen mit {@code #} werden ignoriert,
 *       {@code export NAME=wert} wird als Zuweisung erkannt, aber nicht als
 *       {@code export}-Semantik ausgewertet;</li>
 *   <li>nur einfache {@code ${NAME}}-Referenzen auf Parameter aus
 *       {@code allowedParameterNames}; keine Shellausdruecke, keine Substitutionen, keine
 *       Default-/Command-Expansionen, kein Helm/Groovy/Jenkinsfile;</li>
 *   <li>nicht aufloesbare oder widerspruechliche Werte bleiben offen
 *       ({@link ConfigValue.Resolution#UNRESOLVED} bzw.
 *       {@link ConfigValue.Resolution#CONFLICTING}) und tragen eine Notiz.</li>
 * </ul>
 *
 * <p>Das YAML wird strikt und begrenzt ueber {@link YamlSupport#strictMapper()} geparst; es
 * findet keine Objektinstanziierung aus YAML statt. Mehrere Dokumente je Datei sind zulaessig;
 * das Workload-Dokument wird anhand von {@code kind} ({@code Deployment} oder
 * {@code DeploymentConfig}) erkannt. Mehrere passende Dokumente in derselben Datei sind
 * mehrdeutig. Ein Kubernetes-{@code Secret}-Dokument wird ausdruecklich <em>nicht</em>
 * ausgewertet und nicht ausgeliefert (S07).</p>
 *
 * <p>Nicht ausgeliefert werden: {@code env}/{@code envFrom}/{@code data}/{@code stringData}
 * und alle darunter liegenden Zeilen, Header-Werte, Exec-Argumente und vollstaendige
 * Parameterdateien. Die Zeilennummern bleiben dabei erhalten, weil je Quellzeile genau eine
 * Ergebniszeile entsteht.</p>
 */
public final class TemplateParameterResolver {

    /** Nur diese Rollout-Zeitparameter werden gesucht; keine freie Suche. */
    public static final List<String> ROLLOUT_TIME_PARAMETERS =
            List.of("progressDeadlineSeconds", "minReadySeconds", "revisionHistoryLimit", "activeDeadlineSeconds");

    private static final YAMLMapper YAML = YamlSupport.strictMapper();

    private static final int PENDING_INDENT = Integer.MIN_VALUE;

    private static final Set<String> SENSITIVE_SEGMENTS = Set.of("env", "envFrom", "data", "stringData");

    private static final List<String> CONTAINER_LISTS = List.of("containers", "initContainers");

    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}$");

    private static final Pattern ASSIGNMENT = Pattern.compile("^(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");

    private static final Pattern TRAILING_COMMENT = Pattern.compile("^(.*?)\\s+#.*$");

    private static final List<RolloutField> ROLLOUT_FIELDS = List.of(
            new RolloutField("progressDeadlineSeconds", List.of("/spec/progressDeadlineSeconds")),
            new RolloutField("minReadySeconds", List.of("/spec/minReadySeconds")),
            new RolloutField("revisionHistoryLimit", List.of("/spec/revisionHistoryLimit")),
            new RolloutField("activeDeadlineSeconds",
                    List.of("/spec/activeDeadlineSeconds", "/spec/template/spec/activeDeadlineSeconds")));

    private static final List<String> PROBE_KINDS = List.of("liveness", "readiness", "startup");

    private static final List<String> PROBE_TIMINGS = List.of(
            "initialDelaySeconds", "periodSeconds", "timeoutSeconds",
            "successThreshold", "failureThreshold", "terminationGracePeriodSeconds");

    private static final List<String> RESOURCE_FIELDS =
            List.of("limits.cpu", "limits.memory", "requests.cpu", "requests.memory");

    /** Ergebnis einer Aufloesung ueber mehrere Dateien. */
    public record Result(
            Map<String, ContainerConfig> containers,
            Map<String, ConfigValue> values,
            Map<String, ConfigValue> rolloutTimeParameters,
            List<String> containerNames,
            Map<String, FileReadResult.FileStatus> fileStatuses,
            Map<String, List<String>> fileNotes,
            Map<String, List<String>> deliverableLines,
            List<String> notes) {

        public Result {
            containers = immutable(containers);
            values = immutable(values);
            rolloutTimeParameters = immutable(rolloutTimeParameters);
            containerNames = containerNames == null ? List.of() : List.copyOf(containerNames);
            fileStatuses = immutable(fileStatuses);
            fileNotes = immutableListMap(fileNotes);
            deliverableLines = immutableListMap(deliverableLines);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Status der Datei; {@code READ}, wenn die Datei nicht aufgeloest wurde (Standardfall). */
        public FileReadResult.FileStatus statusOf(String file) {
            return fileStatuses.getOrDefault(file, FileReadResult.FileStatus.READ);
        }

        /**
         * Ob diese Datei ueberhaupt an die Aufloesung uebergeben wurde. Nur dann darf der
         * aufgeloeste Status uebernommen werden; sonst wuerde ein bereits ermittelter
         * Status (etwa {@code NOT_ALLOWED}) faelschlich auf {@code READ} zurueckfallen.
         */
        public boolean knows(String file) {
            return deliverableLines.containsKey(file) || fileStatuses.containsKey(file);
        }

        public List<String> notesOf(String file) {
            return fileNotes.getOrDefault(file, List.of());
        }
    }

    /** Loest Templates und Parameterdateien gemeinsam auf. */
    public Result resolve(Map<String, String> templateContents, Map<String, String> parameterContents,
            List<String> allowedParameterNames) {
        Map<String, String> templates = templateContents == null ? Map.of() : templateContents;
        Map<String, String> parameters = parameterContents == null ? Map.of() : parameterContents;
        List<String> allowed = normalizeNames(allowedParameterNames);

        Map<String, ContainerConfig> containers = new LinkedHashMap<>();
        Map<String, ConfigValue> values = new LinkedHashMap<>();
        Map<String, List<String>> fileNotes = new LinkedHashMap<>();
        Map<String, List<String>> deliverable = new LinkedHashMap<>();
        Map<String, FileReadResult.FileStatus> statuses = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        ParameterTable table = ParameterTable.parse(parameters, allowed, fileNotes);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            deliverable.put(entry.getKey(),
                    sanitizeParameterLines(splitLines(entry.getValue()), allowed));
            statuses.put(entry.getKey(), FileReadResult.FileStatus.READ);
        }

        Map<String, ConfigValue> rolloutByName = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            String file = entry.getKey();
            List<String> lines = splitLines(entry.getValue());
            TemplateFile parsed = parseTemplateFile(file, lines, table, containers, values);
            statuses.put(file, parsed.status());
            fileNotes.put(file, parsed.notes());
            deliverable.put(file, parsed.deliverableLines());
            notes.addAll(parsed.notes());
            parsed.rolloutTimeParameters().forEach(rolloutByName::putIfAbsent);
        }
        fileNotes.values().forEach(notes::addAll);
        notes = dedupe(notes);

        Map<String, ConfigValue> rollout = new LinkedHashMap<>();
        for (String name : ROLLOUT_TIME_PARAMETERS) {
            ConfigValue value = rolloutByName.get(name);
            if (value != null) {
                rollout.put(name, value);
            }
        }

        return new Result(containers, values, rollout, List.copyOf(containers.keySet()), statuses,
                fileNotes, deliverable, notes);
    }

    /** Containerkonfigurationen, nach Containername gekeyed. */
    public Map<String, ContainerConfig> resolveContainers(Map<String, String> templateContents,
            Map<String, String> parameterContents, List<String> allowedParameterNames) {
        return resolve(templateContents, parameterContents, allowedParameterNames).containers();
    }

    /** Ausgewaehlte Rollout-Zeitparameter mit vollstaendiger Herkunft. */
    public Map<String, ConfigValue> resolveRolloutTimeParameters(Map<String, String> templateContents,
            Map<String, String> parameterContents, List<String> allowedParameterNames) {
        return resolve(templateContents, parameterContents, allowedParameterNames).rolloutTimeParameters();
    }

    /** Containernamen in Dokumentreihenfolge. */
    public List<String> findContainerNames(Map<String, String> templateContents,
            Map<String, String> parameterContents, List<String> allowedParameterNames) {
        return resolve(templateContents, parameterContents, allowedParameterNames).containerNames();
    }

    // ------------------------------------------------------------------
    // Templateverarbeitung
    // ------------------------------------------------------------------

    private record TemplateFile(
            FileReadResult.FileStatus status,
            List<String> notes,
            Map<String, ConfigValue> rolloutTimeParameters,
            List<String> deliverableLines) {
    }

    private TemplateFile parseTemplateFile(String file, List<String> lines,
            ParameterTable table, Map<String, ContainerConfig> containers, Map<String, ConfigValue> values) {
        List<String> notes = new ArrayList<>();
        List<Document> documents = splitDocuments(lines);
        List<Document> secrets = new ArrayList<>();
        List<Document> workloads = new ArrayList<>();
        LineIndex mergedIndex = new LineIndex();
        FileReadResult.FileStatus status = FileReadResult.FileStatus.READ;

        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            JsonNode root;
            try {
                root = YAML.readTree(document.text());
            } catch (JacksonException | IllegalArgumentException ex) {
                notes.add("Dokument " + (i + 1) + " in " + file + " ist kein gueltiges YAML: "
                        + YamlSupport.describeParseError(ex) + ". Die Datei wird nicht ausgewertet.");
                return new TemplateFile(FileReadResult.FileStatus.PARSE_ERROR, notes, Map.of(),
                        maskAll(lines, "YAML nicht lesbar"));
            }
            mergedIndex.merge(scanLines(lines, document.startLine(), document.endLineExclusive()));
            if (root == null || root.isNull()) {
                continue;
            }
            if (!root.isObject()) {
                notes.add("Dokument " + (i + 1) + " in " + file + " ist kein YAML-Objekt und wird uebersprungen.");
                continue;
            }
            String kind = scalar(root.get("kind"));
            if ("Secret".equals(kind)) {
                secrets.add(document);
                notes.add("Dokument " + (i + 1) + " in " + file + " hat kind=Secret und wird nicht ausgewertet "
                        + "oder ausgeliefert (S07).");
                continue;
            }
            if ("Deployment".equals(kind) || "DeploymentConfig".equals(kind)) {
                workloads.add(document.withRoot(root));
                continue;
            }
            notes.add("Dokument " + (i + 1) + " in " + file + " hat kind="
                    + (kind == null ? "<ohne kind>" : kind) + " und wird in V1 nicht ausgewertet.");
        }

        if (workloads.size() > 1) {
            notes.add("Mehrere Workload-Dokumente ("
                    + workloads.stream().map(doc -> scalar(doc.root().get("kind"))).toList()
                    + ") in " + file + "; die Zuordnung ist mehrdeutig.");
            status = FileReadResult.FileStatus.AMBIGUOUS;
            return new TemplateFile(status, notes, Map.of(), maskSensitive(lines, mergedIndex, secrets));
        }
        if (workloads.isEmpty()) {
            notes.add("Kein Workload-Dokument (kind Deployment/DeploymentConfig) in " + file + " gefunden.");
            status = FileReadResult.FileStatus.UNSUPPORTED_TYPE;
            return new TemplateFile(status, notes, Map.of(), maskSensitive(lines, mergedIndex, secrets));
        }

        Document workload = workloads.get(0);
        Map<String, ConfigValue> rollout = new LinkedHashMap<>();
        for (RolloutField field : ROLLOUT_FIELDS) {
            for (String pointer : field.pointers()) {
                JsonNode node = at(workload.root(), pointer);
                if (node == null) {
                    continue;
                }
                ConfigValue value = resolveScalar(pointer, node, file,
                        mergedIndex.lineOf(pointer), table, notes);
                if (value != null) {
                    values.put(value.pointer(), value);
                    rollout.putIfAbsent(field.name(), value);
                }
                break;
            }
        }

        extractContainers(file, workload, mergedIndex, table, containers, values, notes);

        return new TemplateFile(status, notes, rollout, maskSensitive(lines, mergedIndex, secrets));
    }

    private void extractContainers(String file, Document workload, LineIndex index, ParameterTable table,
            Map<String, ContainerConfig> containers, Map<String, ConfigValue> values, List<String> notes) {
        for (String listName : CONTAINER_LISTS) {
            String listPointer = "/spec/template/spec/" + listName;
            JsonNode array = at(workload.root(), listPointer);
            if (array == null || !array.isArray()) {
                continue;
            }
            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                if (node == null || !node.isObject()) {
                    continue;
                }
                String base = listPointer + "/" + i;
                String name = scalar(node.get("name"));
                if (name == null || name.isBlank()) {
                    notes.add("Container ohne Namen unter " + base + " in " + file + " wird uebersprungen.");
                    continue;
                }
                if (containers.containsKey(name)) {
                    notes.add("Container '" + name + "' ist in " + file + " mehrfach definiert; "
                            + "der erste Eintrag wird verwendet.");
                    continue;
                }
                containers.put(name, container(file, node, base, index, table, values, notes));
            }
        }
    }

    private ContainerConfig container(String file, JsonNode node, String base, LineIndex index,
            ParameterTable table, Map<String, ConfigValue> values, List<String> notes) {
        String name = scalar(node.get("name"));
        ConfigValue image = resolveScalar(base + "/image", node.get("image"), file, index.lineOf(base + "/image"),
                table, notes);
        if (image != null) {
            values.put(image.pointer(), image);
        }
        String imageSource = image == null || !image.isResolved()
                ? file
                : (image.parameterFile() != null ? image.parameterFile() : file);

        Map<String, Integer> ports = new LinkedHashMap<>();
        JsonNode portArray = node.get("ports");
        if (portArray != null && portArray.isArray()) {
            for (int i = 0; i < portArray.size(); i++) {
                JsonNode port = portArray.get(i);
                if (port == null || !port.isObject()) {
                    continue;
                }
                String portName = scalar(port.get("name"));
                ConfigValue containerPort = resolveScalar(base + "/ports/" + i + "/containerPort",
                        port.get("containerPort"), file, index.lineOf(base + "/ports/" + i + "/containerPort"),
                        table, notes);
                if (containerPort != null) {
                    values.put(containerPort.pointer(), containerPort);
                }
                Integer number = asInt(containerPort, base + "/ports/" + i + "/containerPort", notes);
                if (portName != null && !portName.isBlank() && number != null) {
                    ports.put(portName, number);
                }
            }
        }

        Map<String, String> resources = new LinkedHashMap<>();
        for (String field : RESOURCE_FIELDS) {
            String pointer = base + "/resources/" + field.replace('.', '/');
            ConfigValue value = resolveScalar(pointer, at(node, "/resources/" + field.replace('.', '/')), file,
                    index.lineOf(pointer), table, notes);
            if (value != null) {
                values.put(value.pointer(), value);
                if (value.value() != null) {
                    resources.put(field, value.value());
                }
            }
        }

        Map<String, ProbeConfig> probes = new LinkedHashMap<>();
        for (String kind : PROBE_KINDS) {
            String probePointer = base + "/" + kind + "Probe";
            JsonNode probe = node.get(kind + "Probe");
            if (probe == null || !probe.isObject()) {
                continue;
            }
            probes.put(kind, probe(name, probe, probePointer, file, index, table, values, notes));
        }

        return new ContainerConfig(name, image == null ? null : image.value(), imageSource, ports, probes,
                resources, base);
    }

    private ProbeConfig probe(String containerName, JsonNode node, String base, String file, LineIndex index,
            ParameterTable table, Map<String, ConfigValue> values, List<String> notes) {
        String httpGetPointer = base + "/httpGet";
        String tcpPointer = base + "/tcpSocket";
        String execPointer = base + "/exec";
        JsonNode httpGet = node.get("httpGet");
        JsonNode tcpSocket = node.get("tcpSocket");
        JsonNode exec = node.get("exec");

        ProbeConfig.HandlerKind handler = ProbeConfig.HandlerKind.UNKNOWN;
        if (httpGet != null && httpGet.isObject()) {
            handler = ProbeConfig.HandlerKind.HTTP_GET;
        } else if (tcpSocket != null && tcpSocket.isObject()) {
            handler = ProbeConfig.HandlerKind.TCP_SOCKET;
        } else if (exec != null && exec.isObject()) {
            handler = ProbeConfig.HandlerKind.EXEC;
        }

        String path = null;
        String port = null;
        String scheme = null;
        List<String> headerNames = new ArrayList<>();
        boolean redacted = false;
        List<String> redactionNotes = new ArrayList<>();

        if (httpGet != null && httpGet.isObject()) {
            ConfigValue pathValue = resolveScalar(httpGetPointer + "/path", httpGet.get("path"), file,
                    index.lineOf(httpGetPointer + "/path"), table, notes);
            path = record(values, pathValue);
            ConfigValue portValue = resolveScalar(httpGetPointer + "/port", httpGet.get("port"), file,
                    index.lineOf(httpGetPointer + "/port"), table, notes);
            port = record(values, portValue);
            ConfigValue schemeValue = resolveScalar(httpGetPointer + "/scheme", httpGet.get("scheme"), file,
                    index.lineOf(httpGetPointer + "/scheme"), table, notes);
            scheme = record(values, schemeValue);
            if (scheme == null) {
                // Kubernetes wertet ein fehlendes scheme als HTTP. Der Default wird explizit
                // gesetzt, damit der Vergleich mit der Pod-Konfiguration nicht faelschlich
                // einen Unterschied meldet.
                scheme = "HTTP";
            }
            JsonNode headers = httpGet.get("httpHeaders");
            if (headers != null && headers.isArray()) {
                for (int i = 0; i < headers.size(); i++) {
                    JsonNode header = headers.get(i);
                    String headerName = header == null ? null : scalar(header.get("name"));
                    if (headerName != null && !headerName.isBlank()) {
                        headerNames.add(headerName);
                        values.putIfAbsent(httpGetPointer + "/httpHeaders/" + i + "/name", new ConfigValue(
                                httpGetPointer + "/httpHeaders/" + i + "/name", headerName,
                                ConfigValue.Resolution.TEMPLATE_LITERAL, file,
                                index.lineOf(httpGetPointer + "/httpHeaders/" + i + "/name"), null, null, null,
                                "", "Headername; der Headerwert wird nicht uebernommen."));
                        redacted = true;
                    }
                }
                if (redacted) {
                    redactionNotes.add("Headerwerte werden nicht uebernommen, nur Namen.");
                }
            }
        }
        if (tcpSocket != null && tcpSocket.isObject()) {
            ConfigValue portValue = resolveScalar(tcpPointer + "/port", tcpSocket.get("port"), file,
                    index.lineOf(tcpPointer + "/port"), table, notes);
            port = record(values, portValue);
        }
        boolean execRedacted = false;
        if (exec != null && exec.isObject()) {
            execRedacted = true;
            redacted = true;
            redactionNotes.add("Exec-Argumente werden nicht uebernommen (koennen Zugangsdaten enthalten).");
            values.putIfAbsent(execPointer + "/command", new ConfigValue(execPointer + "/command", null,
                    ConfigValue.Resolution.UNRESOLVED, file, index.lineOf(execPointer + "/command"), null, null,
                    null, "", "Exec-Argumente werden bewusst nicht uebernommen."));
        }

        Map<String, Integer> timings = new LinkedHashMap<>();
        for (String timing : PROBE_TIMINGS) {
            String pointer = base + "/" + timing;
            ConfigValue value = resolveScalar(pointer, node.get(timing), file, index.lineOf(pointer), table, notes);
            if (value != null) {
                values.put(value.pointer(), value);
                timings.put(timing, asInt(value, pointer, notes));
            }
        }

        return new ProbeConfig(containerName, kindOf(base), handler, path, port, scheme, headerNames,
                execRedacted,
                timings.get("initialDelaySeconds"), timings.get("periodSeconds"), timings.get("timeoutSeconds"),
                timings.get("successThreshold"), timings.get("failureThreshold"),
                timings.get("terminationGracePeriodSeconds"),
                redacted, String.join(" ", redactionNotes));
    }

    private static ProbeConfig.ProbeKind kindOf(String probePointer) {
        if (probePointer.endsWith("/livenessProbe")) {
            return ProbeConfig.ProbeKind.LIVENESS;
        }
        if (probePointer.endsWith("/readinessProbe")) {
            return ProbeConfig.ProbeKind.READINESS;
        }
        return ProbeConfig.ProbeKind.STARTUP;
    }

    private static String record(Map<String, ConfigValue> values, ConfigValue value) {
        if (value == null) {
            return null;
        }
        values.put(value.pointer(), value);
        return value.value();
    }

    // ------------------------------------------------------------------
    // Wertaufloesung
    // ------------------------------------------------------------------

    private ConfigValue resolveScalar(String pointer, JsonNode node, String file, Integer line,
            ParameterTable table, List<String> notes) {
        if (node == null || node.isNull() || node.isContainer()) {
            return null;
        }
        String text = scalar(node);
        if (text == null) {
            return null;
        }
        String source = redact(text);
        Matcher matcher = REFERENCE.matcher(text.strip());
        if (matcher.matches()) {
            String name = matcher.group(1);
            ParameterTable.Definition definition = table.definition(name);
            if (!table.isAllowed(name)) {
                notes.add("Parameter '" + name + "' ist nicht in allowedParameterNames; der Wert bleibt offen.");
                return new ConfigValue(pointer, null, ConfigValue.Resolution.UNRESOLVED, file, line, null, null,
                        name, source, "Parameter ist nicht erlaubt.");
            }
            if (definition == null) {
                notes.add("Parameter '" + name + "' ist in den Parameterdateien nicht definiert; "
                        + "der Wert bleibt offen.");
                return new ConfigValue(pointer, null, ConfigValue.Resolution.UNRESOLVED, file, line, null, null,
                        name, source, "Parameter ist nicht definiert.");
            }
            if (definition.conflicting()) {
                notes.add("Parameter '" + name + "' ist widerspruechlich definiert; der Wert bleibt offen.");
                return new ConfigValue(pointer, null, ConfigValue.Resolution.CONFLICTING, file, line,
                        definition.file(), definition.line(), name, source, definition.conflictNote());
            }
            if (definition.value() == null) {
                notes.add("Parameter '" + name + "' hat keinen auswertbaren Wert; der Wert bleibt offen.");
                return new ConfigValue(pointer, null, ConfigValue.Resolution.UNRESOLVED, file, line,
                        definition.file(), definition.line(), name, source, definition.conflictNote());
            }
            String value = redact(definition.value());
            String note = value.equals(definition.value()) ? "" : "Der Parameterwert wurde redigiert.";
            if (!note.isEmpty()) {
                notes.add(note);
            }
            return new ConfigValue(pointer, value, ConfigValue.Resolution.SUBSTITUTED, file, line,
                    definition.file(), definition.line(), name, source, note);
        }
        if (text.indexOf('$') >= 0 || text.indexOf('`') >= 0) {
            notes.add("Der Wert unter " + pointer + " ist ein Ausdruck und wird in V1 nicht ausgewertet.");
            return new ConfigValue(pointer, null, ConfigValue.Resolution.UNRESOLVED, file, line, null, null, null,
                    source, "Nur einfache ${NAME}-Referenzen werden aufgeloest.");
        }
        return new ConfigValue(pointer, text, ConfigValue.Resolution.TEMPLATE_LITERAL, file, line, null, null, null,
                source, "");
    }

    private static Integer asInt(ConfigValue value, String pointer, List<String> notes) {
        if (value == null || value.value() == null) {
            return null;
        }
        String text = value.value().strip();
        if (text.matches("-?\\d{1,9}")) {
            return Integer.valueOf(text);
        }
        notes.add("Der Wert unter " + pointer + " ist keine Zahl und wird nicht als Zeitangabe uebernommen.");
        return null;
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return source == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <K, V> Map<K, List<V>> immutableListMap(Map<K, List<V>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, List<V>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private record RolloutField(String name, List<String> pointers) {
    }

    // ------------------------------------------------------------------
    // Parameterdateien
    // ------------------------------------------------------------------

    /** Zuweisungen aus Parameterdateien, inklusive Herkunft und Konflikten. */
    static final class ParameterTable {

        record Definition(String name, String value, String file, int line, boolean conflicting,
                String conflictNote) {
        }

        private final Map<String, Definition> definitions;
        private final Set<String> allowed;

        private ParameterTable(Map<String, Definition> definitions, Set<String> allowed) {
            this.definitions = definitions;
            this.allowed = allowed;
        }

        boolean isAllowed(String name) {
            return allowed.contains(name);
        }

        Definition definition(String name) {
            return definitions.get(name);
        }

        static ParameterTable parse(Map<String, String> parameterContents, List<String> allowed,
                Map<String, List<String>> fileNotes) {
            Map<String, Definition> definitions = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parameterContents.entrySet()) {
                String file = entry.getKey();
                List<String> lines = splitLines(entry.getValue());
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    Matcher matcher = ASSIGNMENT.matcher(line.strip());
                    if (line.strip().startsWith("#") || !matcher.matches()) {
                        continue;
                    }
                    String name = matcher.group(1);
                    if (!allowed.contains(name)) {
                        continue;
                    }
                    String value = unquote(stripTrailingComment(matcher.group(2)));
                    if (value.indexOf('$') >= 0 || value.indexOf('`') >= 0) {
                        fileNotes.computeIfAbsent(file, key -> new ArrayList<>())
                                .add("Zuweisung '" + name + "' in Zeile " + (i + 1)
                                        + " ist ein Ausdruck und wird nicht ausgewertet.");
                        definitions.putIfAbsent(name, new Definition(name, null, file, i + 1, false,
                                "Wert ist ein Ausdruck und wird nicht ausgewertet."));
                        continue;
                    }
                    Definition existing = definitions.get(name);
                    Definition found = new Definition(name, value, file, i + 1, false, "");
                    if (existing == null) {
                        definitions.put(name, found);
                    } else if (!Objects.equals(existing.value(), value)) {
                        String note = "Widerspruechliche Werte fuer '" + name + "': " + existing.file() + ":"
                                + existing.line() + " und " + file + ":" + (i + 1) + ".";
                        definitions.put(name, new Definition(name, null, existing.file(), existing.line(), true, note));
                        fileNotes.computeIfAbsent(file, key -> new ArrayList<>()).add(note);
                    }
                }
            }
            return new ParameterTable(definitions, Set.copyOf(allowed));
        }
    }

    /**
     * Zeilen einer Parameterdatei fuer die Auslieferung: Zuweisungen nicht erlaubter Parameter
     * werden durch einen Platzhalter ersetzt. Die Zeilenzahl bleibt erhalten, damit
     * Zeilenbezuege gueltig bleiben.
     */
    static List<String> sanitizeParameterLines(List<String> lines, List<String> allowed) {
        Set<String> allowedSet = Set.copyOf(allowed == null ? List.of() : allowed);
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher matcher = ASSIGNMENT.matcher(line.strip());
            if (line.strip().startsWith("#") || !matcher.matches()) {
                result.add(redact(line));
                continue;
            }
            String name = matcher.group(1);
            if (allowedSet.contains(name)) {
                result.add(redact(line));
            } else {
                result.add(maskLine(indentOf(line), "nicht erlaubter Parameter " + name));
            }
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------
    // YAML-Zeilenindex und Maskierung
    // ------------------------------------------------------------------

    private enum Coll { MAP, SEQ }

    private static final class Frame {
        private final Coll coll;
        private int indent;
        private final String path;
        private String lastKeyPath;
        private int itemIndex = -1;
        private String itemPath;

        Frame(Coll coll, int indent, String path) {
            this.coll = coll;
            this.indent = indent;
            this.path = path;
        }
    }

    /** Zeilennummern zu YAML-Pointern sowie die Pfade fuer die Maskierung. */
    static final class LineIndex {
        private final Map<Integer, String> containing = new HashMap<>();
        private final Map<Integer, String> keys = new HashMap<>();
        private final Map<String, Integer> keyLines = new LinkedHashMap<>();

        void containing(int line, String path) {
            if (path != null && !path.isEmpty()) {
                containing.putIfAbsent(line, path);
            }
        }

        void key(int line, String keyPath, String containingPath) {
            keys.put(line, keyPath);
            keyLines.putIfAbsent(keyPath, line);
            containing(line, containingPath);
        }

        void merge(LineIndex other) {
            other.containing.forEach(containing::putIfAbsent);
            other.keys.forEach(keys::putIfAbsent);
            other.keyLines.forEach(keyLines::putIfAbsent);
        }

        /** 1-basierte Zeilennummer eines Pointers oder {@code null}. */
        Integer lineOf(String pointer) {
            return keyLines.get(pointer);
        }

        String keyPathAt(int line) {
            return keys.get(line);
        }

        String containingPathAt(int line) {
            return containing.get(line);
        }

        boolean isEmpty() {
            return keys.isEmpty() && containing.isEmpty();
        }
    }

    /**
     * Ermittelt zu jeder Zeile den umgebenden YAML-Pfad und den Pointer einer Schluesselzeile.
     * Unterstuetzt Block-Mappings, Block-Sequenzen und Sequenzeintraege mit inline beginnendem
     * Mapping ({@code - name: wert}).
     */
    static LineIndex scanLines(List<String> lines, int startLine, int endLineExclusive) {
        LineIndex index = new LineIndex();
        Deque<Frame> stack = new ArrayDeque<>();
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.size(), endLineExclusive - 1);
        for (int i = from; i < to; i++) {
            String raw = lines.get(i);
            int lineNo = i + 1;
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.equals("---") || stripped.equals("...")) {
                continue;
            }
            int col = indentOf(raw);
            String text = raw.substring(col);
            boolean dash = text.equals("-") || text.startsWith("- ");
            while (!stack.isEmpty()) {
                Frame top = stack.peek();
                if (top.indent == PENDING_INDENT) {
                    break;
                }
                if (top.indent > col) {
                    stack.pop();
                    continue;
                }
                if (top.coll == Coll.SEQ && top.indent == col && !dash) {
                    stack.pop();
                    continue;
                }
                break;
            }
            if (dash) {
                Frame seq = stack.peek();
                if (seq == null || seq.coll != Coll.SEQ || seq.indent != col) {
                    String path = "";
                    Frame owner = null;
                    for (Frame frame : stack) {
                        if (frame.coll == Coll.MAP) {
                            owner = frame;
                            break;
                        }
                    }
                    if (owner != null) {
                        path = owner.lastKeyPath == null ? owner.path : owner.lastKeyPath;
                    }
                    seq = new Frame(Coll.SEQ, col, path);
                    stack.push(seq);
                }
                seq.itemIndex++;
                String itemPath = seq.path + "/" + seq.itemIndex;
                String rest = text.length() <= 1 ? "" : text.substring(1).stripLeading();
                if (rest.isEmpty()) {
                    index.containing(lineNo, itemPath);
                    stack.push(new Frame(Coll.MAP, PENDING_INDENT, itemPath));
                    continue;
                }
                if (keySeparator(rest) > 0) {
                    int restCol = col + 1 + (text.length() - 1 - rest.length());
                    Frame itemMap = new Frame(Coll.MAP, restCol, itemPath);
                    stack.push(itemMap);
                    recordKey(index, itemMap, rest, lineNo);
                    continue;
                }
                index.containing(lineNo, seq.path);
                continue;
            }
            Frame map;
            Frame top = stack.peek();
            if (top == null) {
                map = new Frame(Coll.MAP, col, "");
                stack.push(map);
            } else if (top.coll == Coll.MAP && top.indent == PENDING_INDENT) {
                top.indent = col;
                map = top;
            } else if (top.coll == Coll.MAP && top.indent == col) {
                map = top;
            } else {
                String path = top.coll == Coll.MAP
                        ? (top.lastKeyPath == null ? top.path : top.lastKeyPath)
                        : top.path;
                map = new Frame(Coll.MAP, col, path);
                stack.push(map);
            }
            recordKey(index, map, text, lineNo);
        }
        return index;
    }

    private static void recordKey(LineIndex index, Frame map, String text, int lineNo) {
        int separator = keySeparator(text);
        if (separator <= 0) {
            index.containing(lineNo, map.path);
            return;
        }
        String key = unquote(text.substring(0, separator).strip());
        if (key.isEmpty() || key.indexOf(' ') >= 0) {
            index.containing(lineNo, map.path);
            return;
        }
        String keyPath = map.path + "/" + key;
        index.key(lineNo, keyPath, map.path);
        map.lastKeyPath = keyPath;
    }

    private static int keySeparator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' && i > 0 && text.charAt(i - 1) == ' ') {
                return -1;
            }
            if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static int indentOf(String line) {
        int col = 0;
        while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) {
            col++;
        }
        return col;
    }

    /** Ersetzt sensible Bloecke und Secret-Dokumente; die Zeilenzahl bleibt erhalten. */
    static List<String> maskSensitive(List<String> lines, LineIndex index, List<Document> secrets) {
        List<String> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);
            if (inSecretDocument(lineNo, secrets)) {
                result.add(maskLine(indentOf(line), "kind Secret wird nicht uebernommen"));
                continue;
            }
            String keyPath = index.keyPathAt(lineNo);
            String containing = index.containingPathAt(lineNo);
            String sensitive = sensitiveSegment(keyPath);
            if (sensitive == null) {
                sensitive = sensitiveSegment(containing);
            }
            if (sensitive == null) {
                result.add(redact(line));
            } else {
                result.add(maskLine(indentOf(line), sensitive));
            }
        }
        return List.copyOf(result);
    }

    private static boolean inSecretDocument(int line, List<Document> secrets) {
        for (Document document : secrets) {
            if (line >= document.startLine() && line < document.endLineExclusive()) {
                return true;
            }
        }
        return false;
    }

    private static String sensitiveSegment(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (SENSITIVE_SEGMENTS.contains(segment)) {
                return segment;
            }
        }
        return null;
    }

    private static List<String> maskAll(List<String> lines, String reason) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(maskLine(indentOf(line), reason));
        }
        return List.copyOf(result);
    }

    private static String maskLine(int indent, String reason) {
        String prefix = " ".repeat(Math.max(0, indent));
        return prefix + "# [nicht uebernommen: " + redact(reason) + "]";
    }

    // ------------------------------------------------------------------
    // Kleine Helfer
    // ------------------------------------------------------------------

    /** Dokumentgrenzen (1-basiert, Ende exklusiv) fuer {@code ---}-getrennte YAML-Dateien. */
    record Document(int startLine, int endLineExclusive, String text, JsonNode root) {

        Document withRoot(JsonNode newRoot) {
            return new Document(startLine, endLineExclusive, text, newRoot);
        }
    }

    static List<Document> splitDocuments(List<String> lines) {
        List<Document> documents = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            boolean separator = lines.get(i).equals(stripped)
                    && (stripped.equals("---") || stripped.startsWith("--- ") || stripped.equals("..."));
            if (separator) {
                addDocument(documents, lines, start, i);
                start = i + 1;
            }
        }
        addDocument(documents, lines, start, lines.size());
        return documents;
    }

    private static void addDocument(List<Document> documents, List<String> lines, int from, int to) {
        if (from >= to) {
            return;
        }
        List<String> slice = lines.subList(from, to);
        boolean empty = slice.stream().allMatch(line -> line.isBlank() || line.strip().startsWith("#"));
        if (empty) {
            return;
        }
        documents.add(new Document(from + 1, to + 1, String.join("\n", slice), null));
    }

    /** Navigiert ueber einen absoluten JSON-Pointer zu einem Knoten; {@code null}, wenn absent. */
    static JsonNode at(JsonNode root, String pointer) {
        if (root == null || pointer == null || pointer.isEmpty()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : pointer.substring(1).split("/")) {
            if (current == null) {
                return null;
            }
            String name = segment.replace("~1", "/").replace("~0", "~");
            JsonNode next = name.matches("\\d+") ? current.get(Integer.parseInt(name)) : current.get(name);
            if (next == null || next.isMissingNode()) {
                return null;
            }
            current = next;
        }
        return current;
    }

    static String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isContainer()) {
            return null;
        }
        String value = node.asString();
        return value == null ? null : value;
    }

    static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String[] parts = content.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return List.copyOf(lines);
    }

    /** Entfernt einen nicht gequoteten Kommentar und einfache Anfuehrungszeichen. */
    static String unquote(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String stripTrailingComment(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value;
        }
        Matcher matcher = TRAILING_COMMENT.matcher(value);
        return matcher.matches() ? matcher.group(1).strip() : value;
    }

    private static List<String> normalizeNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                boolean present = result.contains(name);
                if (!present) {
                    result.add(name);
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    static String redact(String text) {
        return Redactor.redact(text == null ? "" : text).text();
    }
}
