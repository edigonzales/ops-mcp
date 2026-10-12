package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.util.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests fuer die gekapselten Git-Aufrufe: ausschliesslich lesende Befehle, feste Argumentlisten,
 * eingeschraenkte Kindumgebung und strikte Validierung von Commitkennungen und Pfaden.
 *
 * <p>Diese Tests brauchen kein echtes Git: der {@link FakeProcessRunner} zeichnet die Aufrufe
 * auf und liefert vorgegebene Antworten. Damit ist belegbar, dass ein verbotener Aufruf gar
 * nicht erst entsteht.</p>
 */
class GitProcessTest {

    private static final String COMMIT_A = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String COMMIT_B = "fedcba9876543210fedcba9876543210fedcba98";

    @TempDir
    Path tempDir;

    private FakeProcessRunner runner;
    private GitProcess git;
    private GitObjectReader objects;

    @BeforeEach
    void setUp() {
        runner = new FakeProcessRunner();
        git = new GitProcess(tempDir, runner);
        objects = new GitObjectReader(git);
    }

    @Test
    void childEnvironmentIsRestricted() {
        runner.respond("cat-file", null, 0, "");
        assertTrue(objects.commitExists(COMMIT_A));

        Map<String, String> env = runner.lastCall().environment();
        assertEquals("1", env.get("GIT_CONFIG_NOSYSTEM"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("/bin/false", env.get("GIT_ASKPASS"));
        assertEquals("cat", env.get("GIT_PAGER"));
        assertEquals("", env.get("GIT_EXTERNAL_DIFF"));
        assertEquals("0", env.get("GIT_CONFIG_COUNT"));
        assertEquals("0", env.get("GIT_OPTIONAL_LOCKS"));
        assertEquals("1", env.get("GIT_ATTR_NOSYSTEM"));

        Path globalConfig = Path.of(env.get("GIT_CONFIG_GLOBAL"));
        assertNotNull(globalConfig);
        assertEquals("gitconfig", globalConfig.getFileName().toString());
        assertTrue(Files.isRegularFile(globalConfig), "GIT_CONFIG_GLOBAL muss eine leere Datei sein");
        assertEquals(0L, sizeOf(globalConfig), "die globale Ersatzkonfiguration muss leer sein");

        Path home = Path.of(env.get("HOME"));
        assertTrue(Files.isDirectory(home), "HOME muss auf ein vorhandenes Verzeichnis zeigen");
        assertTrue(isEmptyDirectory(home), "HOME muss leer sein");
        assertEquals(globalConfig.getParent(), home.getParent());
    }

    @Test
    void fixedArgumentsPreventHooksPagersAndExternalDiffs() {
        runner.respond("cat-file", null, 0, "");
        objects.commitExists(COMMIT_A);

        List<String> arguments = runner.lastCall().arguments();
        assertEquals("git", runner.lastCall().executable());
        assertEquals("-C", arguments.get(0));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), arguments.get(1));
        assertEquals(List.of("-c", "core.hooksPath=" + hooksDirectory(arguments).toString(),
                "-c", "diff.external=",
                "-c", "core.pager=cat"),
                arguments.subList(2, 8));

        Path hooks = hooksDirectory(arguments);
        assertTrue(Files.isDirectory(hooks), "das Hooks-Verzeichnis muss existieren");
        assertTrue(isEmptyDirectory(hooks), "das Hooks-Verzeichnis muss leer sein");
    }

    @Test
    void writingGitCommandsAreRejectedBeforeAnyProcessStarts() {
        List<List<String>> forbidden = List.of(
                List.of("commit", "-m", "x"),
                List.of("push", "origin", "main"),
                List.of("fetch", "--all"),
                List.of("pull"),
                List.of("clone", "/tmp/x"),
                List.of("merge", "main"),
                List.of("rebase", "main"),
                List.of("checkout", "main"),
                List.of("switch", "main"),
                List.of("reset", "--hard"),
                List.of("clean", "-fdx"),
                List.of("stash", "push"),
                List.of("apply", "patch.diff"),
                List.of("tag", "v1"),
                List.of("branch", "-d", "main"),
                List.of("config", "--global", "user.name", "x"),
                List.of("remote", "add", "origin", "https://example.invalid/x.git"));

        for (List<String> command : forbidden) {
            GitReadException ex = assertThrows(GitReadException.class, () -> git.run(command),
                    "verbotener Befehl wurde nicht abgewiesen: " + command);
            assertEquals(ErrorCode.SCOPE_DENIED, ex.code(), command.toString());
        }
        assertTrue(runner.calls().isEmpty(), "kein verbotener Aufruf darf einen Prozess starten");
    }

    @Test
    void readOnlyCommandsAreAccepted() {
        runner.respond("status", null, 0, "");
        runner.respond("log", null, 0, "");
        runner.respond("rev-list", null, 0, "");

        assertEquals(0, git.run(List.of("status", "--porcelain")).exitCode());
        assertEquals(0, git.run(List.of("log")).exitCode());
        assertEquals(0, git.run(List.of("rev-list", COMMIT_A)).exitCode());
        assertEquals(List.of("status", "log", "rev-list"), runner.subcommands());

        // Nicht erlaubte Optionsvarianten bleiben abgewiesen.
        assertThrows(GitReadException.class, () -> git.run(List.of("status", "--short")));
        assertEquals(3, runner.calls().size());
    }

    @Test
    void optionInjectionIsRejected() {
        List<List<String>> injections = List.of(
                List.of("cat-file", "-e", "--upload-pack=/bin/sh"),
                List.of("cat-file", "-e", "-x"),
                List.of("show", "--no-index", "a", "b"),
                List.of("ls-tree", "--name-only", "--upload-pack=/bin/sh"),
                List.of("cat-file", "-e", "--output=/tmp/x"));
        for (List<String> injection : injections) {
            assertThrows(GitReadException.class, () -> git.run(injection), injection.toString());
        }
        assertTrue(runner.calls().isEmpty(), "Optionsinjektion darf keinen Prozess starten");
    }

    @Test
    void commitIdentifiersAreStrictlyValidated() {
        assertTrue(GitProcess.isValidCommit(COMMIT_A));
        assertTrue(GitProcess.isValidCommit(COMMIT_A.substring(0, 40)));
        assertTrue(GitProcess.isValidCommit("abcdef1"));
        assertFalse(GitProcess.isValidCommit("abcdef"));
        assertFalse(GitProcess.isValidCommit("HEAD"));
        assertFalse(GitProcess.isValidCommit("head"));
        assertFalse(GitProcess.isValidCommit("main"));
        assertFalse(GitProcess.isValidCommit("origin/main"));
        assertFalse(GitProcess.isValidCommit("@"));
        assertFalse(GitProcess.isValidCommit("HEAD^{commit}"));
        assertFalse(GitProcess.isValidCommit(COMMIT_A + "^{commit}"));
        assertFalse(GitProcess.isValidCommit("--upload-pack=/bin/sh"));
        assertFalse(GitProcess.isValidCommit("abc 123"));
        assertFalse(GitProcess.isValidCommit(""));
        assertFalse(GitProcess.isValidCommit(null));
        assertFalse(GitProcess.isValidCommit("zzzzzzz"));
        assertTrue(GitProcess.isValidCommit(COMMIT_A + "0"), "ein 41-stelliger Hexwert ist syntaktisch zulaessig");
        assertTrue(GitProcess.isValidCommit("a".repeat(64)));
        assertFalse(GitProcess.isValidCommit("a".repeat(65)));
    }

    @Test
    void relativePathsAreStrictlyValidated() {
        assertTrue(GitProcess.isValidRelativePath("templates/deployment.yaml"));
        assertTrue(GitProcess.isValidRelativePath("a.yaml"));
        assertTrue(GitProcess.isValidRelativePath("a-b/c_d.yaml"));
        assertFalse(GitProcess.isValidRelativePath("../a.yaml"));
        assertFalse(GitProcess.isValidRelativePath("a/../../b.yaml"));
        assertFalse(GitProcess.isValidRelativePath("/etc/passwd"));
        assertFalse(GitProcess.isValidRelativePath("-rf"));
        assertFalse(GitProcess.isValidRelativePath("a/-b.yaml"));
        assertFalse(GitProcess.isValidRelativePath("a:b"));
        assertFalse(GitProcess.isValidRelativePath("a\\b"));
        assertFalse(GitProcess.isValidRelativePath("a//b"));
        assertFalse(GitProcess.isValidRelativePath("dir/"));
        assertFalse(GitProcess.isValidRelativePath(""));
        assertFalse(GitProcess.isValidRelativePath(null));
    }

    @Test
    void invalidIdentifiersNeverReachGit() {
        assertThrows(GitReadException.class, () -> objects.readFileAtCommit("main", "a.yaml"));
        assertThrows(GitReadException.class, () -> objects.readFileAtCommit(COMMIT_A, "../escape.yaml"));
        assertThrows(GitReadException.class, () -> objects.readFileAtCommit(COMMIT_A, "-x.yaml"));
        assertThrows(GitReadException.class, () -> objects.commitExists("HEAD"));
        assertThrows(GitReadException.class, () -> objects.pathExistsAtCommit(COMMIT_A, "a:b.yaml"));
        assertThrows(GitReadException.class, () -> objects.isSymbolicLinkAtCommit("origin/main", "a.yaml"));
        assertTrue(runner.calls().isEmpty());

        GitReadException commitEx = assertThrows(GitReadException.class, () -> GitProcess.requireCommit("main"));
        assertEquals(ErrorCode.SCOPE_DENIED, commitEx.code());
        GitReadException pathEx = assertThrows(GitReadException.class,
                () -> GitProcess.requireRelativePath("../x"));
        assertEquals(ErrorCode.SCOPE_DENIED, pathEx.code());
        assertEquals(COMMIT_A, GitProcess.requireCommit(COMMIT_A));
        assertEquals("a.yaml", GitProcess.requireRelativePath("a.yaml"));
    }

    @Test
    void readerUsesFixedArgumentLists() {
        runner.respond("cat-file", null, 0, "");
        runner.respond("ls-tree", "--name-only", 0, TempGitRepo.DEPLOYMENT + "\n");
        runner.respond("ls-tree", null, 0, "100644 blob 0f1e2d\t" + TempGitRepo.DEPLOYMENT + "\n");
        runner.respond("show", null, 0, "kind: Deployment\n");
        runner.respond("rev-parse", "--verify", 0, COMMIT_B + "\n");
        runner.respond("rev-parse", "--show-toplevel", 0, tempDir.toString() + "\n");

        assertTrue(objects.commitExists(COMMIT_A));
        assertEquals("kind: Deployment\n", objects.readFileAtCommit(COMMIT_A, TempGitRepo.DEPLOYMENT));
        assertTrue(objects.pathExistsAtCommit(COMMIT_A, TempGitRepo.DEPLOYMENT));
        assertFalse(objects.isSymbolicLinkAtCommit(COMMIT_A, TempGitRepo.DEPLOYMENT));
        assertEquals(COMMIT_B, objects.resolveHead());
        assertEquals(tempDir.toString(), objects.currentWorktreePath());

        List<List<String>> argumentLists = runner.gitArgumentLists();
        assertEquals(6, argumentLists.size(), argumentLists.toString());
        assertEquals(List.of("cat-file", "-e", COMMIT_A + "^{commit}"), argumentLists.get(0));
        assertEquals(List.of("show", COMMIT_A + ":" + TempGitRepo.DEPLOYMENT), argumentLists.get(1));
        assertEquals(List.of("ls-tree", "--name-only", COMMIT_A, "--", TempGitRepo.DEPLOYMENT),
                argumentLists.get(2));
        assertEquals(List.of("ls-tree", COMMIT_A, "--", TempGitRepo.DEPLOYMENT), argumentLists.get(3));
        assertEquals(List.of("rev-parse", "--verify", "HEAD^{commit}"), argumentLists.get(4));
        assertEquals(List.of("rev-parse", "--show-toplevel"), argumentLists.get(5));
        assertTrue(GitProcess.READ_ONLY_COMMANDS.containsAll(runner.subcommands()));
    }

    @Test
    void symbolicLinkModeIsDetected() {
        runner.respond("ls-tree", null, 0, "120000 blob abcdef0123\t" + TempGitRepo.ESCAPE + "\n");
        assertTrue(objects.isSymbolicLinkAtCommit(COMMIT_A, TempGitRepo.ESCAPE));

        FakeProcessRunner absent = new FakeProcessRunner();
        GitObjectReader absentReader = new GitObjectReader(new GitProcess(tempDir, absent));
        absent.respond("ls-tree", null, 0, "");
        assertFalse(absentReader.isSymbolicLinkAtCommit(COMMIT_A, TempGitRepo.DEPLOYMENT));
    }

    @Test
    void missingCommitIsFalseNotAnException() {
        runner.respond("cat-file", null, 128, "", "fatal: Not a valid object name " + COMMIT_A);
        assertFalse(objects.commitExists(COMMIT_A));

        runner.respond("show", null, 128, "", "fatal: path 'x' does not exist");
        GitReadException ex = assertThrows(GitReadException.class,
                () -> objects.readFileAtCommit(COMMIT_A, "x.yaml"));
        assertEquals(ErrorCode.NOT_FOUND, ex.code());

        FakeProcessRunner tree = new FakeProcessRunner();
        GitObjectReader treeReader = new GitObjectReader(new GitProcess(tempDir, tree));
        tree.respond("ls-tree", null, 0, "");
        assertFalse(treeReader.pathExistsAtCommit(COMMIT_A, TempGitRepo.DEPLOYMENT));
    }

    @Test
    void timeoutAndMissingBinaryAreReportedDistinctly() {
        ExecResult timeout = new ExecResult(-1, "", "abgebrochen", true, false, 10_000, List.of("git"));
        runner.respondWith("cat-file", null, timeout);
        GitReadException timeoutEx = assertThrows(GitReadException.class, () -> objects.commitExists(COMMIT_A));
        assertEquals(ErrorCode.REQUEST_TIMEOUT, timeoutEx.code());
        assertTrue(timeoutEx.toError("deployment-config").retryable());

        FakeProcessRunner missing = new FakeProcessRunner();
        GitObjectReader missingReader = new GitObjectReader(new GitProcess(tempDir, missing));
        missing.respondWith("cat-file", null,
                new ExecResult(-1, "", "Prozess konnte nicht gestartet werden", false, false, 0, List.of("git")));
        GitReadException missingEx = assertThrows(GitReadException.class,
                () -> missingReader.commitExists(COMMIT_A));
        assertEquals(ErrorCode.CONFIG_INVALID, missingEx.code());
        assertFalse(missingEx.toError("deployment-config").retryable());
    }

    @Test
    void outputsAreRedacted() {
        runner.respond("show", null, 0,
                "TOKEN=fixture-canary-0123456789\nurl: https://example.invalid/x?token=abcdef0123456789\n");
        String content = objects.readFileAtCommit(COMMIT_A, "params/app.env");
        assertFalse(content.contains("fixture-canary-0123456789"), content);
        assertFalse(content.contains("abcdef0123456789"), content);
        assertTrue(content.contains("[redigiert]"), content);

        runner.respond("rev-parse", "--verify", 0,
                "https://ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@example.invalid/repo.git\n");
        String head = objects.resolveHead();
        assertNotNull(head);
        assertFalse(head.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), head);
        assertTrue(head.contains("[redigiert]"), head);
    }

    @Test
    void inheritedGitOverridesAreDetected() {
        assertTrue(GitProcess.conflictingVariables(Map.of()).isEmpty());
        assertTrue(GitProcess.conflictingVariables(Map.of("PATH", "/usr/bin")).isEmpty());
        assertEquals(List.of("GIT_DIR"), GitProcess.conflictingVariables(Map.of("GIT_DIR", "/tmp/other")));
        assertEquals(List.of("GIT_DIR", "GIT_WORK_TREE"),
                GitProcess.conflictingVariables(Map.of("GIT_DIR", "/tmp/other", "GIT_WORK_TREE", "/tmp/w")));
        assertTrue(GitProcess.conflictingVariables(Map.of("GIT_DIR", " ")).isEmpty());
        assertEquals(List.of("PATH"), List.copyOf(GitProcess.restrictedBaseEnvironment().keySet()));
    }

    @Test
    void configuredTimeoutAndBudgetArePassedThrough() {
        GitProcess shortLived = new GitProcess(tempDir, runner, Duration.ofMillis(1500), 4096);
        runner.respond("cat-file", null, 0, "");
        assertTrue(new GitObjectReader(shortLived).commitExists(COMMIT_A));
        assertEquals(Duration.ofMillis(1500), runner.lastCall().timeout());
        assertEquals(4096, runner.lastCall().maxOutputBytes());
    }

    private static Path hooksDirectory(List<String> arguments) {
        for (int i = 0; i < arguments.size() - 1; i++) {
            if ("-c".equals(arguments.get(i)) && arguments.get(i + 1).startsWith("core.hooksPath=")) {
                return Path.of(arguments.get(i + 1).substring("core.hooksPath=".length()));
            }
        }
        throw new AssertionError("core.hooksPath fehlt in " + arguments);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static boolean isEmptyDirectory(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
