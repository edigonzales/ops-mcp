package ch.so.agi.ops.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Echte Prozessausfuehrung ohne Shell. Der Aufruf erfolgt ueber {@link ProcessBuilder} mit
 * einer Argumentliste; Standardausgabe und Standardfehler werden getrennt und begrenzt
 * gelesen. Bei Zeitueberschreitung wird der Prozess beendet.
 */
public final class DefaultProcessRunner implements ProcessRunner {

    /** Legt die Kindumgebung fest; {@code null} uebernimmt die Elternumgebung unveraendert. */
    private final Map<String, String> baseEnvironment;

    public DefaultProcessRunner(Map<String, String> baseEnvironment) {
        this.baseEnvironment = baseEnvironment == null ? null : Map.copyOf(baseEnvironment);
    }

    public static DefaultProcessRunner inheritingEnvironment() {
        return new DefaultProcessRunner(null);
    }

    @Override
    public ExecResult exec(String executable, List<String> arguments, Path workingDir,
            Map<String, String> extraEnv, Duration timeout, long maxOutputBytes) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (baseEnvironment != null || extraEnv != null) {
            Map<String, String> environment = builder.environment();
            if (baseEnvironment != null) {
                environment.clear();
                environment.putAll(baseEnvironment);
            }
            if (extraEnv != null) {
                environment.putAll(extraEnv);
            }
        }

        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            return new ExecResult(-1, "", "Prozess konnte nicht gestartet werden: " + ex.getMessage(), false, false,
                    (System.nanoTime() - started) / 1_000_000, redactedCommandLine(command));
        }

        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<byte[]> stdoutFuture = readers.submit(() -> readLimited(process.getInputStream(), maxOutputBytes));
        Future<byte[]> stderrFuture = readers.submit(() -> readLimited(process.getErrorStream(), maxOutputBytes));
        int exitCode;
        boolean timedOut = false;
        try {
            boolean finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            exitCode = timedOut ? -1 : process.exitValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            exitCode = -1;
            timedOut = true;
        } finally {
            readers.shutdownNow();
        }

        byte[] stdoutBytes = await(stdoutFuture);
        byte[] stderrBytes = await(stderrFuture);
        long durationMillis = (System.nanoTime() - started) / 1_000_000;
        boolean truncated = stdoutBytes.length >= maxOutputBytes || stderrBytes.length >= maxOutputBytes;
        String stderrText = new String(stderrBytes, StandardCharsets.UTF_8);
        return new ExecResult(exitCode, new String(stdoutBytes, StandardCharsets.UTF_8),
                ch.so.agi.ops.security.Redactor.redact(stderrText).text(), timedOut, truncated, durationMillis,
                redactedCommandLine(command));
    }

    private static byte[] await(Future<byte[]> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (ExecutionException | TimeoutException ex) {
            return new byte[0];
        }
    }

    private static byte[] readLimited(InputStream in, long limit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (total >= limit) {
                    continue;
                }
                int allowed = (int) Math.min(read, limit - total);
                out.write(buffer, 0, allowed);
                total += allowed;
            }
        } catch (IOException ex) {
            // Abgebrochene Ausgabe wird wie eine Truncation behandelt.
        }
        return out.toByteArray();
    }

    /**
     * Befehlszeile fuer Fehlermeldungen: Argumente werden gekuerzt und redigiert. Zugangsdaten
     * duerfen nie in Fehlermeldungen erscheinen.
     */
    static List<String> redactedCommandLine(List<String> command) {
        List<String> result = new ArrayList<>(command.size());
        for (String part : command) {
            String redacted = ch.so.agi.ops.security.Redactor.redact(part).text();
            result.add(redacted.length() > 80 ? redacted.substring(0, 80) + "..." : redacted);
        }
        return List.copyOf(result);
    }
}
