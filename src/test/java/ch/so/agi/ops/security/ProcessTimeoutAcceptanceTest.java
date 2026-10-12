package ch.so.agi.ops.security;

import ch.so.agi.ops.util.DefaultProcessRunner;
import ch.so.agi.ops.util.ExecResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S11 – lang laufender bzw. nie endender Quellprozess.
 *
 * <p>Belegt: Der Prozessstarter beendet einen Prozess nach dem Timeout, markiert ihn als
 * {@code timedOut}, kappt zu grosse Ausgabe sichtbar als {@code outputTruncated} und laesst
 * keinen Prozess weiterlaufen. Die Tests laufen nur auf Unix-Systemen mit {@code /bin/sleep}
 * und {@code /bin/cat}; es wird kein echtes {@code oc} und kein Netz verwendet.</p>
 */
class ProcessTimeoutAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final long MAX_AUSGABE_BYTES = 4096;

    private static Set<Long> kindprozesse() {
        try (var kinder = ProcessHandle.current().children()) {
            return kinder.map(ProcessHandle::pid).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static List<String> lebendeNeueKindprozesse(Set<Long> vorher) {
        try (var kinder = ProcessHandle.current().children()) {
            return kinder.filter(handle -> !vorher.contains(handle.pid()))
                    .filter(ProcessHandle::isAlive)
                    .map(handle -> handle.pid() + " "
                            + handle.info().commandLine().orElse("<Befehlszeile nicht lesbar>"))
                    .toList();
        }
    }

    @Test
    @DisplayName("S11: /bin/sleep 30 wird nach 300 ms Timeout beendet und laeuft nicht weiter")
    void langLaufenderProzessWirdNachTimeoutBeendet() {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of("/bin/sleep")),
                "Der Test setzt ein Unix-System mit /bin/sleep voraus.");
        Set<Long> vorher = kindprozesse();
        DefaultProcessRunner runner = DefaultProcessRunner.inheritingEnvironment();

        long start = System.nanoTime();
        ExecResult result = runner.exec("/bin/sleep", List.of("30"), tempDir, null, Duration.ofMillis(300),
                64 * 1024);
        long dauerMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.timedOut(), "Der Prozess muss als Zeitueberschreitung markiert sein.");
        assertEquals(-1, result.exitCode(), "Ein abgebrochener Prozess hat keinen gueltigen Exitcode.");
        assertTrue(dauerMillis < 20_000, "Der Aufruf darf nicht haengen; Dauer: " + dauerMillis + " ms");
        assertTrue(result.durationMillis() >= 200, "Die Laufzeit muss gemessen werden.");

        List<String> ueberreste = lebendeNeueKindprozesse(vorher);
        assertTrue(ueberreste.isEmpty(), "Der Prozess darf nicht weiterlaufen: " + ueberreste);
    }

    @Test
    @DisplayName("S11: zu grosse Ausgabe wird als outputTruncated markiert")
    void zuGrosseAusgabeWirdAlsTruncationMarkiert() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of("/bin/cat")),
                "Der Test setzt ein Unix-System mit /bin/cat voraus.");
        Path grosseDatei = tempDir.resolve("gross.txt");
        Files.writeString(grosseDatei, ("x".repeat(199) + "\n").repeat(1000), StandardCharsets.UTF_8);
        Set<Long> vorher = kindprozesse();
        DefaultProcessRunner runner = DefaultProcessRunner.inheritingEnvironment();

        ExecResult result = runner.exec("/bin/cat", List.of(grosseDatei.toString()), tempDir, null,
                Duration.ofSeconds(20), MAX_AUSGABE_BYTES);

        assertTrue(result.outputTruncated(), "Die gekappte Ausgabe muss als Truncation markiert sein.");
        assertFalse(result.timedOut(), "Der Prozess endet von selbst.");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().length() <= MAX_AUSGABE_BYTES,
                "Die Ausgabe muss auf das Budget begrenzt bleiben: " + result.stdout().length());
        assertTrue(Files.size(grosseDatei) > MAX_AUSGABE_BYTES);
        assertEquals(List.of(), lebendeNeueKindprozesse(vorher));
    }

    @Test
    @DisplayName("S11: eine nicht startbare Programmdatei ergibt keinen Absturz und keinen Haenger")
    void nichtStartbareProgrammdateiErgibtKlarenFehler() {
        DefaultProcessRunner runner = DefaultProcessRunner.inheritingEnvironment();

        ExecResult result = runner.exec(tempDir.resolve("gibt-es-nicht").toString(), List.of(), tempDir, null,
                Duration.ofSeconds(5), 1024);

        assertFalse(result.success());
        assertFalse(result.timedOut());
        assertTrue(result.stderr().contains("konnte nicht gestartet werden")
                || !result.stderr().isBlank(), result.stderr());
    }
}
