package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.config.YamlSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrolliertes Test-Executable fuer den echten {@link OcClusterReader}.
 *
 * <p>Es wird ein ausfuehrbares Python3-Skript in einem temporaeren Verzeichnis erzeugt. Das
 * Skript prueft die Argumente, protokolliert <em>jeden</em> Aufruf als eine Zeile JSON und
 * gibt synthetische Rohantworten aus den hinterlegten Regeln zurueck. Ein nicht hinterlegter
 * Aufruf endet mit Exitcode 127 und wird zusaetzlich protokolliert. Es wird niemals ein
 * echtes {@code oc} aus dem {@code PATH} ausgefuehrt: Der Test setzt
 * {@code ocExecutable} ausdruecklich auf dieses Skript.</p>
 *
 * <p>{@link #assertOnlyAllowedCalls()} prueft die Whitelist: erstes Argument aus
 * {@code get}, {@code version}, {@code config}, {@code logs}, {@code whoami}, keine verbotenen
 * Verben, kein {@code --follow}, kein {@code exec} und kein {@code config use-context}.</p>
 */
public final class FakeOc {

    /** Erstes Argument, das ein lesender oc-Aufruf haben darf. */
    public static final Set<String> ALLOWED_FIRST_ARGUMENTS = Set.of("get", "version", "config", "logs", "whoami");

    /** Verbotene Verben; ein Vorkommen als Argument laesst den Test scheitern. */
    public static final Set<String> FORBIDDEN_VERBS = Set.of(
            "apply", "create", "replace", "patch", "delete", "edit", "scale", "rollout", "exec", "attach",
            "port-forward", "proxy", "cp", "rsh", "debug", "drain", "cordon", "uncordon", "annotate", "label",
            "set", "expose", "login", "logout", "project", "new-app", "start-build", "cancel-build",
            "import-image", "process", "adm", "auth", "policy", "idle", "rsync", "observe", "wait",
            "use-context", "set-context", "use-project", "config-replace");

    private static final String SCRIPT_NAME = "fake-oc";
    private static final String SPEC_NAME = "spec.json";
    private static final String CALLS_NAME = "calls.jsonl";
    private static final String UNEXPECTED_NAME = "unexpected.jsonl";
    private static final String COMPLETED_NAME = "completed.jsonl";
    private static final String CHILD_ENV_NAME = "child-env.json";

    private static final String PYTHON = "/usr/bin/python3";

    private final Path directory;
    private final Path executable;
    private final Path specFile;
    private final Path callsFile;
    private final Path unexpectedFile;
    private final Path completedFile;
    private final Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
    private final JsonMapper mapper = YamlSupport.jsonMapper();

    private FakeOc(Path directory) {
        this.directory = directory;
        this.executable = directory.resolve(SCRIPT_NAME);
        this.specFile = directory.resolve(SPEC_NAME);
        this.callsFile = directory.resolve(CALLS_NAME);
        this.unexpectedFile = directory.resolve(UNEXPECTED_NAME);
        this.completedFile = directory.resolve(COMPLETED_NAME);
    }

    /** Erzeugt das Fake-oc in einem (bereits vorhandenen) Verzeichnis. */
    public static FakeOc create(Path directory) throws IOException {
        Files.createDirectories(directory);
        FakeOc fake = new FakeOc(directory);
        Files.writeString(fake.executable, festerSkriptText(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(fake.executable, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ex) {
            fake.executable.toFile().setExecutable(true, true);
        }
        fake.writeSpec();
        return fake;
    }

    /** Pfad des ausfuehrbaren Fake-oc; wird als {@code ocExecutable} konfiguriert. */
    public Path executable() {
        return executable;
    }

    public Path directory() {
        return directory;
    }

    public Path callsFile() {
        return callsFile;
    }

    public Path completedFile() {
        return completedFile;
    }

    public Path unexpectedFile() {
        return unexpectedFile;
    }

    /** Hinterlegt eine erfolgreiche Antwort fuer einen Aufrufschluessel. */
    public FakeOc respond(String key, String stdout) throws IOException {
        return rule(key, ruleMap(stdout, "", 0, 0));
    }

    /** Hinterlegt eine Fehlerantwort (Exitcode ungleich null) fuer einen Aufrufschluessel. */
    public FakeOc respondFailure(String key, int exitCode, String stderr) throws IOException {
        return rule(key, ruleMap("", stderr, exitCode, 0));
    }

    /** Hinterlegt eine langsame Antwort; dient dem Timeoutnachweis. */
    public FakeOc respondSlow(String key, double sleepSeconds, String stdout) throws IOException {
        return rule(key, ruleMap(stdout, "", 0, sleepSeconds));
    }

    private FakeOc rule(String key, Map<String, Object> rule) throws IOException {
        rules.put(key, rule);
        writeSpec();
        return this;
    }

    private static Map<String, Object> ruleMap(String stdout, String stderr, int exitCode, double sleepSeconds) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("stdout", stdout);
        rule.put("stderr", stderr);
        rule.put("exit", exitCode);
        if (sleepSeconds > 0) {
            rule.put("sleepSeconds", BigDecimal.valueOf(sleepSeconds));
        }
        return rule;
    }

    private void writeSpec() throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", rules);
        Files.writeString(specFile, mapper.writeValueAsString(spec), StandardCharsets.UTF_8);
    }

    /** Alle protokollierten Aufrufe in Reihenfolge. */
    public List<List<String>> calls() {
        return readJsonLines(callsFile).stream()
                .map(node -> {
                    List<String> arguments = new ArrayList<>();
                    node.forEach(argument -> arguments.add(argument.asString()));
                    return List.copyOf(arguments);
                })
                .toList();
    }

    /** Aufrufschluessel aller protokollierten Aufrufe. */
    public List<String> callKeys() {
        return calls().stream().map(FakeOc::ruleKey).toList();
    }

    /** Aufrufe, die keiner Regel entsprachen. */
    public List<String> unexpectedKeys() {
        return readJsonLines(unexpectedFile).stream()
                .map(node -> node.get("key") == null ? "" : node.get("key").asString())
                .toList();
    }

    /** Aufrufschluessel, deren Prozess tatsaechlich bis zum Ende gelaufen ist. */
    public List<String> completedKeys() {
        List<String> keys = new ArrayList<>();
        for (String line : readLines(completedFile)) {
            if (!line.isBlank()) {
                keys.add(line.trim());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * Proxy-Ausnahmen, die die Kindumgebung tatsaechlich gesehen hat. Es werden bewusst nur
     * {@code NO_PROXY} und {@code no_proxy} erfasst; andere Proxyvariablen werden nicht
     * protokolliert.
     */
    public Map<String, String> childNoProxyEnvironment() {
        Path file = directory.resolve(CHILD_ENV_NAME);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : List.of("NO_PROXY", "no_proxy")) {
                JsonNode value = node.get(key);
                if (value != null && value.isString()) {
                    values.put(key, value.asString());
                }
            }
            return Map.copyOf(values);
        } catch (IOException | RuntimeException ex) {
            return Map.of();
        }
    }

    /** true, wenn mindestens ein Aufruf dem Schluessel entsprach und vollstaendig durchlief. */
    public boolean processCompleted(String key) {
        return completedKeys().contains(key);
    }

    public boolean hasUnexpectedCalls() {
        return !unexpectedKeys().isEmpty();
    }

    /** Fuegt eine rohe Aufrufzeile hinzu; nur fuer Negativtests der Whitelist. */
    public void appendRawCall(List<String> arguments) throws IOException {
        List<String> quoted = new ArrayList<>();
        for (String argument : arguments) {
            quoted.add(mapper.writeValueAsString(argument));
        }
        Files.writeString(callsFile, "[" + String.join(",", quoted) + "]\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Prueft, dass ausschliesslich erlaubte lesende Aufrufe protokolliert wurden. */
    public void assertOnlyAllowedCalls() {
        assertTrue(unexpectedKeys().isEmpty(), "Fake-oc wurde unerwartet aufgerufen: " + unexpectedKeys());
        for (List<String> arguments : calls()) {
            assertArgumentsAllowed(arguments);
        }
    }

    /** Whitelistpruefung einer einzelnen Argumentliste. */
    public static void assertArgumentsAllowed(List<String> arguments) {
        assertFalse(arguments.isEmpty(), "oc wurde ohne Argumente aufgerufen.");
        String verb = arguments.get(0);
        assertTrue(ALLOWED_FIRST_ARGUMENTS.contains(verb),
                "Nicht erlaubtes oc-Verb: '" + verb + "' in " + arguments);
        for (String argument : arguments) {
            String lower = argument.toLowerCase(Locale.ROOT);
            assertFalse(lower.equals("--follow") || lower.startsWith("--follow="),
                    "Verbotenes --follow in " + arguments);
            assertFalse(FORBIDDEN_VERBS.contains(lower),
                    "Verbotenes Verb/Argument '" + argument + "' in " + arguments);
            assertFalse(lower.startsWith("--exec") || lower.startsWith("--attach"),
                    "Verbotenes Flag '" + argument + "' in " + arguments);
        }
        if ("config".equals(verb)) {
            assertEquals("current-context", arguments.size() > 1 ? arguments.get(1) : null,
                    "Nur 'oc config current-context' ist erlaubt: " + arguments);
        }
    }

    /** Aufrufschluessel wie im Python-Skript: Verb, optional Ressource/Objekt, optional +previous. */
    public static String ruleKey(List<String> arguments) {
        if (arguments.isEmpty()) {
            return "";
        }
        StringBuilder key = new StringBuilder(arguments.get(0));
        if (arguments.size() > 1 && !arguments.get(1).startsWith("-")) {
            key.append(' ').append(arguments.get(1));
        }
        if (arguments.contains("--previous")) {
            key.append(" +previous");
        }
        return key.toString();
    }

    /** Feste Aufrufschluessel der Vorabpruefungen. */
    public static final String KEY_CONTEXT = "config current-context";
    public static final String KEY_SERVER = "whoami";
    public static final String KEY_VERSION = "version";
    public static final String KEY_GET_DEPLOYMENT = "get deployment";
    public static final String KEY_GET_DEPLOYMENT_CONFIG = "get deploymentconfig";
    public static final String KEY_GET_REPLICA_SETS = "get replicasets";
    public static final String KEY_GET_REPLICATION_CONTROLLERS = "get replicationcontrollers";
    public static final String KEY_GET_PODS = "get pods";
    public static final String KEY_GET_EVENTS = "get events";
    public static final String KEY_GET_EVENTS_K8S_IO = "get events.events.k8s.io";

    /** Aufrufschluessel fuer Logs eines Pods. */
    public static String logKey(String pod, boolean previous) {
        return "logs " + pod + (previous ? " +previous" : "");
    }

    /** Setzt die ueblichen erfolgreichen Vorabpruefungen. */
    public FakeOc withSuccessfulPreflight() throws IOException {
        respond(KEY_CONTEXT, OcFixtures.CONTEXT + "\n");
        respond(KEY_SERVER, OcFixtures.SERVER + "\n");
        respond(KEY_VERSION, OcFixtures.VERSION_JSON);
        return this;
    }

    private List<JsonNode> readJsonLines(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : readLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            nodes.add(mapper.readTree(line));
        }
        return List.copyOf(nodes);
    }

    private static List<String> readLines(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return List.of();
        }
    }

    /** Pfad des Python3-Interpreters, den das Skript verwendet. */
    public static Path pythonPath() {
        return Path.of(PYTHON);
    }

    /** true, wenn der Test auf diesem System laufen kann. */
    public static boolean pythonAvailable() {
        return Files.isExecutable(pythonPath());
    }

    private static String festerSkriptText() {
        return """
                #!/usr/bin/env python3
                # Synthetisches Fake-oc fuer deterministische Tests.
                # Kein echtes oc, kein Netzwerk, keine Schreiboperation auf einem Cluster.
                import json
                import os
                import sys
                import time

                HERE = os.path.dirname(os.path.abspath(__file__))
                LOG_FILE = os.path.join(HERE, "calls.jsonl")
                UNEXPECTED_FILE = os.path.join(HERE, "unexpected.jsonl")
                COMPLETED_FILE = os.path.join(HERE, "completed.jsonl")
                SPEC_FILE = os.path.join(HERE, "spec.json")
                CHILD_ENV_FILE = os.path.join(HERE, "child-env.json")


                def record_child_environment():
                    # Nur die beiden Proxy-Ausnahmen, die der Adapter pflegt.
                    values = {
                        "NO_PROXY": os.environ.get("NO_PROXY", ""),
                        "no_proxy": os.environ.get("no_proxy", ""),
                    }
                    with open(CHILD_ENV_FILE, "w", encoding="utf-8") as handle:
                        json.dump(values, handle, ensure_ascii=False)
                        handle.flush()
                        os.fsync(handle.fileno())


                def append_line(path, value):
                    with open(path, "a", encoding="utf-8") as handle:
                        if isinstance(value, str):
                            handle.write(value + "\\n")
                        else:
                            handle.write(json.dumps(value, ensure_ascii=False) + "\\n")
                        handle.flush()
                        os.fsync(handle.fileno())


                def rule_key(args):
                    if not args:
                        return ""
                    key = args[0]
                    if len(args) > 1 and not args[1].startswith("-"):
                        key = key + " " + args[1]
                    if "--previous" in args:
                        key = key + " +previous"
                    return key


                def main():
                    args = sys.argv[1:]
                    append_line(LOG_FILE, args)
                    record_child_environment()
                    with open(SPEC_FILE, "r", encoding="utf-8") as handle:
                        spec = json.load(handle)
                    key = rule_key(args)
                    rule = spec.get("rules", {}).get(key)
                    if rule is None:
                        append_line(UNEXPECTED_FILE, {"key": key, "args": args})
                        sys.stderr.write("fake-oc: unerwarteter Aufruf fuer Schluessel '" + key + "'\\n")
                        return 127
                    sleep_seconds = float(rule.get("sleepSeconds", 0))
                    if sleep_seconds > 0:
                        time.sleep(sleep_seconds)
                    if rule.get("stdout"):
                        sys.stdout.write(rule["stdout"])
                        sys.stdout.flush()
                    if rule.get("stderr"):
                        sys.stderr.write(rule["stderr"])
                        sys.stderr.flush()
                    append_line(COMPLETED_FILE, key)
                    return int(rule.get("exit", 0))


                if __name__ == "__main__":
                    sys.exit(main())
                """;
    }
}
