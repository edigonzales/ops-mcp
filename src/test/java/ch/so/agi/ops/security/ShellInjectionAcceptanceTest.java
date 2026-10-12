package ch.so.agi.ops.security;

import ch.so.agi.ops.adapter.openshift.OcCommandBuilder;
import ch.so.agi.ops.cli.CliArguments;
import ch.so.agi.ops.cli.CliRunner;
import ch.so.agi.ops.cli.ExitCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S04 – Shellmetazeichen und Optionsinjektion in Namen.
 *
 * <p>Belegt: Der Adapter erzeugt Argumentlisten fuer {@code ProcessBuilder} und niemals einen
 * Shelltext. Metazeichen bleiben genau ein Argument und werden nicht interpretiert;
 * Optionsinjektion ueber einen fuehrenden Bindestrich und Steuerzeichen wird abgewiesen.
 * Unbekannte Befehle (u. a. {@code deploy}, {@code fix}, {@code apply}) enden mit Exitcode 2.</p>
 */
class ShellInjectionAcceptanceTest {

    @TempDir
    Path tempDir;

    /** Werte mit Shellmetazeichen, aber ohne Steuerzeichen. */
    private static final List<String> METAZEICHEN = List.of(
            "app; touch /tmp/pwned",
            "app | tee /tmp/pwned",
            "app && rm -rf /",
            "app || true",
            "app$(id)",
            "app`id`",
            "app > /tmp/pwned",
            "$(cat /etc/passwd)",
            "`whoami`",
            "app'quote\"double");

    private static final List<String> SHELLINTERPRETER = List.of("sh", "bash", "zsh", "cmd", "/bin/sh", "/bin/bash",
            "eval", "pwsh");

    private OcCommandBuilder builder() {
        return new OcCommandBuilder(tempDir.resolve("kubeconfig-fixture").toString(), "fixture-context");
    }

    private static void assertEinArgumentOhneShell(List<String> arguments, String erwartetesArgument) {
        assertEquals(1, arguments.stream().filter(erwartetesArgument::equals).count(),
                "Der Wert muss genau einmal und unveraendert in der Argumentliste stehen: " + arguments);
        for (String verboten : SHELLINTERPRETER) {
            assertFalse(arguments.contains(verboten),
                    "Es darf kein Shellinterpreter in der Argumentliste stehen: " + arguments);
        }
    }

    @Test
    @DisplayName("S04: Metazeichen im Podnamen bleiben ein einzelnes Argument ohne Shell")
    void metazeichenImPodnamenBleibenEinArgument() {
        for (String name : METAZEICHEN) {
            List<String> arguments = builder().getPod(AcceptanceSupport.NAMESPACE, name);

            assertEinArgumentOhneShell(arguments, name);
            assertEquals("get", arguments.get(0));
            assertEquals("pods", arguments.get(1));
        }
    }

    @Test
    @DisplayName("S04: Metazeichen in Namespace und Selektor bleiben ein einzelnes Argument")
    void metazeichenInNamespaceUndSelektorBleibenEinArgument() {
        for (String wert : METAZEICHEN) {
            assertEinArgumentOhneShell(builder().getPods(wert, "app=fixture"), wert);
            assertEinArgumentOhneShell(builder().getPods(AcceptanceSupport.NAMESPACE, wert), wert);
        }
    }

    @Test
    @DisplayName("S04: Metazeichen im Containernamen bleiben ein einzelnes Argument ohne Shell")
    void metazeichenImContainernamenBleibenEinArgument() {
        for (String wert : METAZEICHEN) {
            List<String> arguments = builder().logs(AcceptanceSupport.NAMESPACE, "fixture-app-0", wert, false,
                    Instant.parse("2026-10-01T10:00:00Z"), 4096);

            assertEinArgumentOhneShell(arguments, wert);
            int containerFlag = arguments.indexOf("-c");
            assertTrue(containerFlag >= 0, arguments.toString());
            assertEquals(wert, arguments.get(containerFlag + 1),
                    "-c ist das Containerflag von oc und traegt genau den Wert als ein Argument.");
            assertFalse(arguments.contains("--follow"), "Ein Logaufruf darf nie --follow verwenden.");
        }
    }

    @Test
    @DisplayName("S04: fuehrender Bindestrich (Optionsinjektion) wird abgewiesen")
    void optionsinjektionWirdAbgewiesen() {
        List<String> verboten = List.of("--kubeconfig=/etc/passwd", "-o", "--context=evil", "-n", "--previous");

        for (String wert : verboten) {
            assertThrows(IllegalArgumentException.class,
                    () -> builder().getPod(AcceptanceSupport.NAMESPACE, wert), "Name: " + wert);
            assertThrows(IllegalArgumentException.class,
                    () -> builder().getPods(wert, "app=fixture"), "Namespace: " + wert);
            assertThrows(IllegalArgumentException.class,
                    () -> builder().getPods(AcceptanceSupport.NAMESPACE, wert), "Selektor: " + wert);
            assertThrows(IllegalArgumentException.class,
                    () -> builder().logs(AcceptanceSupport.NAMESPACE, "fixture-app-0", wert, false, null, 0),
                    "Container: " + wert);
        }
    }

    @Test
    @DisplayName("S04: Steuerzeichen in Namen werden abgewiesen")
    void steuerzeichenWerdenAbgewiesen() {
        List<String> verboten = List.of("pod\nname", "pod\rname", "pod\tname", "pod\u0000name",
                "app\n--kubeconfig=/etc/passwd");

        for (String wert : verboten) {
            assertThrows(IllegalArgumentException.class,
                    () -> builder().getPod(AcceptanceSupport.NAMESPACE, wert));
            assertThrows(IllegalArgumentException.class,
                    () -> builder().getPods(AcceptanceSupport.NAMESPACE, wert));
            assertThrows(IllegalArgumentException.class,
                    () -> builder().logs(AcceptanceSupport.NAMESPACE, "fixture-app-0", wert, false, null, 0));
        }
    }

    @Test
    @DisplayName("S04: erlaubte Kennungen erzeugen genau die festen, lesenden Argumente")
    void erlaubteKennungenErzeugenFesteArgumente() {
        List<String> arguments = builder().logs(AcceptanceSupport.NAMESPACE, "fixture-app-0", "app", true, null,
                4096);

        assertEquals(List.of("logs", "fixture-app-0", "-c", "app", "--kubeconfig",
                tempDir.resolve("kubeconfig-fixture").toString(), "--context", "fixture-context", "-n",
                AcceptanceSupport.NAMESPACE, "--tail", "1000", "--limit-bytes", "4096", "--previous"), arguments);
        assertFalse(builder().version().contains("--follow"));
        assertFalse(builder().whoamiServer().contains("--follow"));
    }

    /** Fuehrt die CLI aus und zeichnet Exitcode, stdout und stderr auf. */
    private static CliRun runCli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliRunner runner = new CliRunner(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), "1.0.0");
        int exitCode = runner.run(args);
        return new CliRun(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record CliRun(int exitCode, String out, String err) {
    }

    @Test
    @DisplayName("S04: unbekannte Befehle enden mit Exitcode 2")
    void unbekannteBefehleEndenMitExitcode2() {
        for (String befehl : List.of("deploy", "fix", "apply", "rollback", "trigger")) {
            CliRun run = runCli(befehl);

            assertEquals(2, run.exitCode(), "Befehl: " + befehl);
            assertEquals(ExitCodes.INVALID_INPUT_OR_CONFIG, run.exitCode());
            assertTrue(run.err().contains("unbekannter Befehl"), run.err());
            assertTrue(run.out().isEmpty(), "Es darf kein Ergebnis auf stdout erscheinen: " + run.out());
        }
    }

    @Test
    @DisplayName("S04: die Nutzungsanleitung nennt keinen aendernden Befehl")
    void nutzungsanleitungNenntKeinenAenderndenBefehl() {
        for (String befehl : List.of("deploy", "fix", "apply", "rollback", "trigger", "delete", "scale")) {
            java.util.regex.Pattern ganzesWort = java.util.regex.Pattern
                    .compile("(?m)(^|\\s)" + java.util.regex.Pattern.quote(befehl) + "($|\\s)");
            assertFalse(ganzesWort.matcher(CliArguments.USAGE).find(),
                    "Verbotener Befehl in der Nutzung: " + befehl);
            assertEquals(befehl, CliArguments.parse(new String[] {befehl}).command());
        }
        assertTrue(CliArguments.USAGE.contains("doctor"));
        assertTrue(CliArguments.USAGE.contains("collect"));
    }
}
