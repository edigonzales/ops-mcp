package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.util.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Synthetisches lokales Git-Repository fuer die Adaptertests.
 *
 * <p>Das Repository wird im Test mit dem {@code git} aus dem PATH aufgebaut (fuer Aufbau und
 * Selbsttest zulaessig). Die Zusicherung der Tests ist, dass der <em>Produktivpfad</em> keine
 * schreibenden oder ausfuehrenden Git-Aufrufe macht: Alle Aufrufe werden aufgezeichnet, und
 * Hooks, Pager und externe Diff-Programme legen bei Ausfuehrung eine Markerdatei an.</p>
 *
 * <p>Inhalt: zwei Commits mit unterschiedlichen Probe-Werten, erlaubte und unerlaubte Dateien,
 * eine Templatedatei mit {@code ${VAR}}-Referenzen, eine Parameterdatei, ein
 * {@code kind: Secret}-Dokument, ein Sidecar-Container, ein Symlink aus dem Repository heraus,
 * ausfuehrbare Hooks sowie eine {@code core.pager}/{@code diff.external}-Konfiguration.</p>
 */
final class TempGitRepo {

    static final String DEPLOYMENT = "templates/deployment.yaml";
    static final String SECRET = "templates/secret.yaml";
    static final String UNALLOWED = "templates/unallowed.yaml";
    static final String ESCAPE = "escape.yaml";
    static final String PARAMETERS = "params/app.env";
    static final String OUTSIDE_CONTENT = "outside-target: fixture-outside-canary-0123456789";

    /** Werte in Commit A (aeltere Revision). */
    static final int A_INITIAL_DELAY = 10;
    static final int A_FAILURE_THRESHOLD = 3;
    static final int A_PROGRESS_DEADLINE = 600;
    /** Werte in Commit B (HEAD). */
    static final int B_INITIAL_DELAY = 30;
    static final int B_FAILURE_THRESHOLD = 5;
    static final int B_PROGRESS_DEADLINE = 900;

    private final Path root;
    private final Path outsideFile;
    private final Path markerDir;
    private final String commitA;
    private final String commitB;

    private TempGitRepo(Path root, Path outsideFile, Path markerDir, String commitA, String commitB) {
        this.root = root;
        this.outsideFile = outsideFile;
        this.markerDir = markerDir;
        this.commitA = commitA;
        this.commitB = commitB;
    }

    /** Prueft defensiv, ob ein brauchbares {@code git} im PATH liegt. */
    static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static TempGitRepo create(Path tempDir) throws IOException, InterruptedException {
        Path root = tempDir.resolve("repo");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve("target.yaml");
        Files.writeString(outsideFile, OUTSIDE_CONTENT + "\n", StandardCharsets.UTF_8);
        Path markerDir = tempDir.resolve("markers");
        Files.createDirectories(markerDir);

        git(root, "init", "-q", ".");
        git(root, "config", "user.email", "fixture@example.invalid");
        git(root, "config", "user.name", "Fixture");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "advice.detachedHead", "false");

        writeFiles(root, A_INITIAL_DELAY, A_FAILURE_THRESHOLD, A_PROGRESS_DEADLINE);
        Files.createSymbolicLink(root.resolve(ESCAPE), outsideFile);
        git(root, "add", "-A");
        git(root, "commit", "-qm", "Commit A");
        String commitA = git(root, "rev-parse", "HEAD").strip();

        writeFiles(root, B_INITIAL_DELAY, B_FAILURE_THRESHOLD, B_PROGRESS_DEADLINE);
        git(root, "add", "-A");
        git(root, "commit", "-qm", "Commit B");
        String commitB = git(root, "rev-parse", "HEAD").strip();

        // Hooks und externe Programme erst nach den Commits: Marker koennen nur durch den
        // Produktivpfad entstehen.
        writeExecutable(root.resolve(".git/hooks/post-checkout"), "#!/bin/sh\n"
                + "touch " + quote(markerDir.resolve("hook-post-checkout")) + "\n");
        writeExecutable(root.resolve(".git/hooks/pre-commit"), "#!/bin/sh\n"
                + "touch " + quote(markerDir.resolve("hook-pre-commit")) + "\n");
        Path pager = tempDir.resolve("hostile-pager.sh");
        writeExecutable(pager, "#!/bin/sh\n" + "touch " + quote(markerDir.resolve("pager")) + "\ncat\n");
        Path externalDiff = tempDir.resolve("hostile-diff.sh");
        writeExecutable(externalDiff, "#!/bin/sh\n" + "touch " + quote(markerDir.resolve("external-diff")) + "\n");
        git(root, "config", "core.pager", pager.toString());
        git(root, "config", "diff.external", externalDiff.toString());

        return new TempGitRepo(root, outsideFile, markerDir, commitA, commitB);
    }

    /**
     * Selbsttest der Fixture: Ein echter Checkout muss den Hook ausfuehren. Damit ist belegt,
     * dass ein fehlender Marker nach dem Lesezugriff tatsaechlich bedeutet, dass kein Checkout
     * stattgefunden hat. Liefert {@code false}, wenn der Hook nicht ausfuehrbar ist.
     */
    boolean proveHookIsLive() throws IOException, InterruptedException {
        String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD").strip();
        git(root, "checkout", "-q", commitA);
        git(root, "checkout", "-q", branch);
        boolean live = Files.exists(markerDir.resolve("hook-post-checkout"));
        clearMarkers();
        return live;
    }

    void clearMarkers() throws IOException {
        if (!Files.isDirectory(markerDir)) {
            return;
        }
        try (var stream = Files.list(markerDir)) {
            for (Path marker : stream.toList()) {
                Files.deleteIfExists(marker);
            }
        }
    }

    List<String> markers() throws IOException {
        if (!Files.isDirectory(markerDir)) {
            return List.of();
        }
        try (var stream = Files.list(markerDir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    Path root() {
        return root;
    }

    Path outsideFile() {
        return outsideFile;
    }

    Path markerDir() {
        return markerDir;
    }

    String commitA() {
        return commitA;
    }

    String commitB() {
        return commitB;
    }

    String head() throws IOException, InterruptedException {
        return git(root, "rev-parse", "HEAD").strip();
    }

    /** Obere Allowlist der Dateien, die der Adapter lesen darf. */
    List<String> allowedFiles() {
        return List.of(DEPLOYMENT, SECRET, ESCAPE, PARAMETERS);
    }

    /** Hashes der Arbeitsbaumdateien; Symlinks werden als Linkziel erfasst. */
    Map<String, String> worktreeHashes() throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String relative : List.of(DEPLOYMENT, SECRET, UNALLOWED, PARAMETERS)) {
            Path file = root.resolve(relative);
            hashes.put(relative, Files.exists(file) ? Hashes.sha256Hex(file) : "<fehlt>");
        }
        Path link = root.resolve(ESCAPE);
        hashes.put(ESCAPE, Files.isSymbolicLink(link)
                ? Hashes.sha256Hex(Files.readSymbolicLink(link).toString())
                : "<kein Symlink>");
        return hashes;
    }

    private static void writeFiles(Path root, int initialDelay, int failureThreshold, int progressDeadline)
            throws IOException {
        Path templates = root.resolve("templates");
        Path params = root.resolve("params");
        Files.createDirectories(templates);
        Files.createDirectories(params);
        Files.writeString(templates.resolve("deployment.yaml"),
                deploymentYaml(initialDelay, failureThreshold, progressDeadline), StandardCharsets.UTF_8);
        Files.writeString(templates.resolve("secret.yaml"), secretYaml(), StandardCharsets.UTF_8);
        Files.writeString(templates.resolve("unallowed.yaml"), unallowedYaml(), StandardCharsets.UTF_8);
        Files.writeString(params.resolve("app.env"), parameterFile(), StandardCharsets.UTF_8);
    }

    private static String deploymentYaml(int initialDelay, int failureThreshold, int progressDeadline) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: fixture-app
                spec:
                  progressDeadlineSeconds: %d
                  minReadySeconds: ${MIN_READY_SECONDS}
                  revisionHistoryLimit: 3
                  replicas: 2
                  template:
                    spec:
                      containers:
                        - name: main
                          image: ${IMAGE_REF}
                          ports:
                            - name: http
                              containerPort: 8080
                          resources:
                            limits:
                              cpu: 500m
                              memory: 512Mi
                            requests:
                              cpu: 100m
                              memory: 256Mi
                          env:
                            - name: DB_PASSWORD
                              value: fixture-env-canary-0123456789
                          livenessProbe:
                            httpGet:
                              path: ${HEALTH_PATH}
                              port: 8080
                              scheme: HTTP
                              httpHeaders:
                                - name: X-Probe-Token
                                  value: fixture-header-canary-0123456789
                            initialDelaySeconds: %d
                            periodSeconds: 10
                            timeoutSeconds: 1
                            successThreshold: 1
                            failureThreshold: %d
                          readinessProbe:
                            tcpSocket:
                              port: http
                            initialDelaySeconds: 5
                            periodSeconds: 5
                            failureThreshold: 2
                          startupProbe:
                            exec:
                              command:
                                - /bin/sh
                                - -c
                                - check --token fixture-exec-canary-0123456789
                            failureThreshold: 30
                            periodSeconds: 5
                        - name: sidecar
                          image: registry.example.invalid/sidecar:1.2.3
                          readinessProbe:
                            httpGet:
                              path: ${HEALTH_PATH}
                              port: 9090
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: fixture-config
                data:
                  log.level: info
                """.formatted(progressDeadline, initialDelay, failureThreshold);
    }

    private static String secretYaml() {
        return """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: fixture-secret
                type: Opaque
                stringData:
                  token: fixture-secret-canary-0123456789
                """;
    }

    private static String unallowedYaml() {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: fixture-unallowed
                spec:
                  template:
                    spec:
                      containers:
                        - name: unallowed
                          image: registry.example.invalid/unallowed:1.0.0
                """;
    }

    private static String parameterFile() {
        return """
                # Synthetische Fixtureparameter; keine echten Zugangsdaten.
                IMAGE_REF=registry.example.invalid/fixture-app:1.0.0
                MIN_READY_SECONDS=5
                HEALTH_PATH="/healthz"
                PROGRESS_DEADLINE=600
                NOT_ALLOWED_PARAM=fixture-not-allowed-canary-0123456789
                DB_PASSWORD=fixture-env-canary-0123456789
                """;
    }

    private static void writeExecutable(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        path.toFile().setExecutable(true, false);
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /** Fuehrt {@code git} im Testaufbau aus und verlangt Erfolg. */
    static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", arguments) + " hing.");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " fehlgeschlagen: " + output);
        }
        return output;
    }
}
