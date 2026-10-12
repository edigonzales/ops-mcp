package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.PathSafety;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gekapselte, ausschliesslich <em>lesende</em> Git-Aufrufe auf einen lokalen Checkout.
 *
 * <p>Diese Klasse bietet keinen schreibenden Befehl an: kein {@code commit}, {@code push},
 * {@code fetch}, {@code pull}, {@code clone}, {@code merge}, {@code rebase},
 * {@code checkout}, {@code switch}, {@code reset}, {@code clean}, {@code stash},
 * {@code apply}, {@code tag}, {@code branch -d}, {@code config --global}. Die erlaubte
 * Befehlsmenge ist fest auf {@code rev-parse}, {@code cat-file}, {@code ls-tree},
 * {@code show}, {@code log}, {@code status --porcelain} und {@code rev-list} begrenzt; jede
 * andere Angabe wird abgewiesen, bevor ein Prozess gestartet wird.</p>
 *
 * <p>Die Kindumgebung wird absichtlich eingeschraenkt: System- und Benutzerkonfiguration
 * werden abgeschaltet, Home zeigt auf ein leeres Verzeichnis, ein Pager wird nicht gestartet,
 * Hooks und externe Diff-/Textconv-Programme koennen nicht laufen, und Zugriffsversuche auf
 * das Netz (Terminal-Prompt, Askpass) sind ausgeschlossen. Zusaetzlich werden
 * {@code -c core.hooksPath=<leeres Verzeichnis>}, {@code -c diff.external=} und
 * {@code -c core.pager=cat} als Argumente uebergeben, damit auch eine praeparierte
 * Repositorykonfiguration wirkungslos bleibt.</p>
 *
 * <p>Objektkennungen werden streng validiert: ein Commit ist nur ein vollstaendiger oder
 * eindeutig aufloesbarer Hex-Hash ({@code [0-9a-fA-F]{7,64}}). Bezeichner wie {@code HEAD},
 * {@code main}, {@code origin/main}, {@code @} oder {@code ^{...}} sind ausdruecklich
 * unzulaessig, weil sie aus einer Modelleingabe stammen koennten. Dateipfade muessen
 * {@link PathSafety#isSafeRelativePath(String)} erfuellen, duerfen kein Segment mit {@code -}
 * beginnen (Optionsinjektion) und kein {@code :} enthalten (Git-Pfadsyntax).</p>
 */
public final class GitProcess {

    /** Programmname; Git wird aus dem PATH der freigegebenen Umgebung verwendet. */
    public static final String GIT_EXECUTABLE = "git";

    /** Standardtimeout eines einzelnen Git-Aufrufs. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Standardbudget fuer stdout und stderr eines einzelnen Aufrufs. */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 4L * 1024 * 1024;

    /** Maximale Laenge eines einzelnen Arguments. */
    public static final int MAX_ARGUMENT_LENGTH = 512;

    /**
     * Erlaubte Git-Befehle. Alle sind lesend; {@code status} wird nur mit
     * {@code --porcelain} verwendet.
     */
    public static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "rev-parse", "cat-file", "ls-tree", "show", "log", "status", "rev-list");

    /**
     * Zulaessige Optionsargumente der lesenden Aufrufe. Alles andere, insbesondere
     * {@code --upload-pack=...}, {@code -c ...} oder ein Pfad, der mit {@code -} beginnt,
     * wird abgewiesen.
     */
    public static final Set<String> ALLOWED_FLAGS = Set.of(
            "-e", "-z", "--", "--name-only", "--porcelain", "--verify", "--quiet", "--no-ext-diff",
            "--show-toplevel");

    /**
     * Variablen der Elternumgebung, die die Objektaufloesung still umlenken koennen. Sie
     * werden nicht uebernommen; eine gesetzte Variable fuehrt zu einem ausdruecklichen Fehler,
     * weil sonst ein anderes Repository gelesen werden koennte.
     */
    public static final List<String> INHERITED_OVERRIDES = List.of(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR", "GIT_NAMESPACE");

    private static final Pattern COMMIT_PATTERN = Pattern.compile("[0-9a-fA-F]{7,64}");

    /** Gemeinsames, leeres Haertungsverzeichnis; enthaelt keine Daten. */
    private static volatile Path hardeningRoot;

    /** Verzeichnis mit leeren Hooks ({@code core.hooksPath}). */
    private static final String HOOKS_DIR = "hooks";

    /** Leere globale Git-Konfiguration ({@code GIT_CONFIG_GLOBAL}); funktioniert auch auf macOS. */
    private static final String EMPTY_CONFIG = "gitconfig";

    /** Leeres Home der Kindumgebung. */
    private static final String EMPTY_HOME = "home";

    private final Path repositoryRoot;
    private final ProcessRunner runner;
    private final Duration timeout;
    private final long maxOutputBytes;

    public GitProcess(Path repositoryRoot, ProcessRunner runner) {
        this(repositoryRoot, runner, DEFAULT_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public GitProcess(Path repositoryRoot, ProcessRunner runner, Duration timeout, long maxOutputBytes) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        this.runner = Objects.requireNonNull(runner, "runner");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout muss positiv sein.");
        }
        this.maxOutputBytes = maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
        List<String> conflicts = conflictingVariables(System.getenv());
        if (!conflicts.isEmpty()) {
            throw new GitReadException(ErrorCode.CONFIG_INVALID,
                    "Die Elternumgebung setzt Git-Variablen, die die Zuordnung des Repositories "
                            + "veraendern koennen: " + String.join(", ", conflicts) + ".",
                    "Diese Variablen vor dem Start entfernen; der Agent liest nur den konfigurierten Checkout.");
        }
    }

    /**
     * Fuehrt einen lesenden Git-Befehl aus. Das Ergebnis ist auf das Ausgabebudget begrenzt und
     * wird bei Zeitueberschreitung beendet.
     *
     * @param gitArguments Befehlsname und Argumente ohne das Programm selbst, z. B.
     *                     {@code ["cat-file", "-e", "<sha>^{commit}"]}
     */
    public ExecResult run(List<String> gitArguments) {
        ExecResult result = runner.exec(GIT_EXECUTABLE, command(gitArguments), null, environment(), timeout,
                maxOutputBytes);
        if (result == null) {
            throw new GitReadException(ErrorCode.INTERNAL_ERROR,
                    "Der Prozessrunner hat kein Ergebnis geliefert.",
                    "Lokale Installation pruefen; dies ist kein Git-Fehler.");
        }
        return result;
    }

    /** Vollstaendige Argumentliste inklusive Haertung, wie sie dem Prozess uebergeben wird. */
    public List<String> command(List<String> gitArguments) {
        List<String> validated = validate(gitArguments);
        List<String> command = new ArrayList<>(baseArguments().size() + validated.size());
        command.addAll(baseArguments());
        command.addAll(validated);
        return List.copyOf(command);
    }

    /** Feste Argumente vor dem eigentlichen Befehl. */
    public List<String> baseArguments() {
        Path root = hardeningRoot();
        return List.of(
                "-C", repositoryRoot.toString(),
                "-c", "core.hooksPath=" + root.resolve(HOOKS_DIR),
                "-c", "diff.external=",
                "-c", "core.pager=cat");
    }

    /**
     * Eingeschraenkte Umgebung des Kindprozesses. Alle Aufrufe erhalten sie vollstaendig, damit
     * weder Hooks noch externe Programme noch Netzwerkprompts wirksam werden koennen.
     */
    public Map<String, String> environment() {
        Path root = hardeningRoot();
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv("PATH");
        env.put("PATH", path == null || path.isBlank() ? "/usr/bin:/bin" : path);
        env.put("HOME", root.resolve(EMPTY_HOME).toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", root.resolve(EMPTY_CONFIG).toString());
        env.put("GIT_CONFIG_COUNT", "0");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/bin/false");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_EXTERNAL_DIFF", "");
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        return Map.copyOf(env);
    }

    /**
     * Minimale Basisumgebung fuer {@code DefaultProcessRunner}. Damit schlagen Variablen der
     * Elternumgebung nicht durch; der Produktivpfad verwendet sie zusammen mit
     * {@code DefaultProcessRunner}.
     */
    public static Map<String, String> restrictedBaseEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv("PATH");
        env.put("PATH", path == null || path.isBlank() ? "/usr/bin:/bin" : path);
        return Map.copyOf(env);
    }

    /** Variablen der uebergebenen Umgebung, die eine stille Umlenkung bewirken wuerden. */
    public static List<String> conflictingVariables(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String name : INHERITED_OVERRIDES) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public Duration timeout() {
        return timeout;
    }

    public long maxOutputBytes() {
        return maxOutputBytes;
    }

    /**
     * Erlaubte Objektkennung: vollstaendiger oder eindeutig aufloesbarer Hex-Hash
     * ({@code [0-9a-fA-F]{7,64}}). Ref-Ausdruecke wie {@code HEAD}, {@code main} oder
     * {@code ^{...}} sind ausdruecklich unzulaessig.
     */
    public static boolean isValidCommit(String value) {
        return value != null && COMMIT_PATTERN.matcher(value).matches();
    }

    /**
     * Erlaubter relativer Dateipfad: sicher gegen Traversal, keine Optionsinjektion, keine
     * Git-Pfadsyntax ({@code :}), kein Backslash und keine Steuerzeichen.
     */
    public static boolean isValidRelativePath(String value) {
        if (!PathSafety.isSafeRelativePath(value)) {
            return false;
        }
        if (value.indexOf(':') >= 0 || value.endsWith("/")) {
            return false;
        }
        if (value.startsWith("-")) {
            return false;
        }
        for (String segment : value.split("/")) {
            if (segment.startsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /** Erzwingt eine gueltige Commitkennung; sonst {@code SCOPE_DENIED}. */
    public static String requireCommit(String value) {
        if (!isValidCommit(value)) {
            throw new GitReadException(ErrorCode.SCOPE_DENIED,
                    "Unzulaessige Commitkennung: " + PathSafety.describe(value)
                            + ". Erlaubt ist nur ein Hex-Hash [0-9a-fA-F]{7,64}.",
                    "Die Revision aus strukturierten Builddaten oder einer Betreiberangabe beziehen; "
                            + "keine Ref-Ausdruecke wie HEAD oder main verwenden.");
        }
        return value;
    }

    /** Erzwingt einen gueltigen relativen Dateipfad; sonst {@code SCOPE_DENIED}. */
    public static String requireRelativePath(String value) {
        if (!isValidRelativePath(value)) {
            throw new GitReadException(ErrorCode.SCOPE_DENIED,
                    "Unzulaessiger relativer Dateipfad: " + PathSafety.describe(value),
                    "Nur konfigurierte und freigegebene Dateien innerhalb des Checkouts lesen.");
        }
        return value;
    }

    private List<String> validate(List<String> gitArguments) {
        if (gitArguments == null || gitArguments.isEmpty()) {
            throw new GitReadException(ErrorCode.INVALID_INPUT, "Es wurde kein Git-Befehl angegeben.");
        }
        String subcommand = gitArguments.get(0);
        if (!READ_ONLY_COMMANDS.contains(subcommand)) {
            throw new GitReadException(ErrorCode.SCOPE_DENIED,
                    "Nicht erlaubter Git-Befehl: " + PathSafety.describe(subcommand)
                            + ". Es sind ausschliesslich lesende Befehle zulaessig.",
                    "Read-only-Betrieb: keine schreibenden Git-Operationen verwenden.");
        }
        List<String> validated = new ArrayList<>(gitArguments.size());
        validated.add(subcommand);
        for (int i = 1; i < gitArguments.size(); i++) {
            String argument = gitArguments.get(i);
            if (argument == null) {
                throw new GitReadException(ErrorCode.INVALID_INPUT, "Ein Git-Argument war leer.");
            }
            if (argument.length() > MAX_ARGUMENT_LENGTH) {
                throw new GitReadException(ErrorCode.INPUT_TOO_LONG,
                        "Ein Git-Argument ueberschreitet " + MAX_ARGUMENT_LENGTH + " Zeichen.");
            }
            if (argument.indexOf('\u0000') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new GitReadException(ErrorCode.INVALID_INPUT,
                        "Ein Git-Argument enthaelt Steuerzeichen.");
            }
            if (argument.startsWith("-") && !ALLOWED_FLAGS.contains(argument)) {
                throw new GitReadException(ErrorCode.SCOPE_DENIED,
                        "Nicht erlaubtes Git-Optionsargument: " + PathSafety.describe(argument),
                        "Optionen werden nicht aus Eingaben uebernommen.");
            }
            validated.add(argument);
        }
        return List.copyOf(validated);
    }

    /** Leeres Haertungsverzeichnis; wird einmal je JVM angelegt und enthaelt keine Nutzdaten. */
    private static Path hardeningRoot() {
        Path existing = hardeningRoot;
        if (existing != null) {
            return existing;
        }
        synchronized (GitProcess.class) {
            if (hardeningRoot == null) {
                try {
                    Path root = Files.createTempDirectory("ops-git-hardening-");
                    Files.createDirectories(root.resolve(HOOKS_DIR));
                    Files.createFile(root.resolve(EMPTY_CONFIG));
                    Files.createDirectories(root.resolve(EMPTY_HOME));
                    root.toFile().deleteOnExit();
                    root.resolve(EMPTY_CONFIG).toFile().deleteOnExit();
                    root.resolve(HOOKS_DIR).toFile().deleteOnExit();
                    root.resolve(EMPTY_HOME).toFile().deleteOnExit();
                    hardeningRoot = root;
                } catch (IOException ex) {
                    throw new GitReadException(ErrorCode.CONFIG_INVALID,
                            "Das leere Haertungsverzeichnis fuer Git konnte nicht angelegt werden.",
                            "Schreibrechte im temporaeren Verzeichnis pruefen.");
                }
            }
            return hardeningRoot;
        }
    }

    /** Redigierte, gekuerzte Beschreibung einer Ausgabe fuer Notizen. */
    static String redactedSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String single = Redactor.redact(text.strip()).text().replace('\n', ' ').replace('\r', ' ');
        return PathSafety.describe(single);
    }
}
