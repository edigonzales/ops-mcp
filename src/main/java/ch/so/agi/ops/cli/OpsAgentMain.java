package ch.so.agi.ops.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Einstiegspunkt des Launchers.
 *
 * <p>stdout traegt ausschliesslich das Ergebnis bzw. im {@code serve}-Betrieb JSON-RPC. Alle
 * Logausgaben gehen nach stderr und werden redigiert. Der Launcher startet das gebaute
 * Programm und loest keinen Gradle-Build aus.</p>
 */
public final class OpsAgentMain {

    public static final String VERSION = "1.0.0";

    private OpsAgentMain() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
                StandardCharsets.UTF_8);
        int exitCode = new CliRunner(out, err, VERSION).run(args);
        out.flush();
        err.flush();
        if (exitCode != ExitCodes.OK) {
            System.exit(exitCode);
        }
    }

    /** Nur fuer Tests: fuehrt einen Befehl ohne {@code System.exit} aus. */
    public static int runForTesting(String[] args, PrintStream out, PrintStream err) {
        return new CliRunner(out, err, VERSION).run(args);
    }
}
