package ch.so.agi.ops.util;

import java.util.List;

/**
 * Ergebnis eines externen Aufrufs.
 *
 * @param exitCode    Exitcode; {@code -1}, wenn der Prozess abgebrochen wurde
 * @param stdout      Standardausgabe, auf das Budget begrenzt
 * @param stderr      Standardfehlerausgabe, redigiert und begrenzt
 * @param timedOut    true, wenn der Prozess wegen Zeitueberschreitung beendet wurde
 * @param outputTruncated true, wenn die Ausgabe das Budget erreicht hat
 * @param durationMillis gemessene Laufzeit
 * @param commandLine redigierte, gekuerzte Befehlsbeschreibung fuer Fehlermeldungen
 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated,
        long durationMillis,
        List<String> commandLine) {

    public ExecResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        commandLine = commandLine == null ? List.of() : List.copyOf(commandLine);
    }

    public boolean success() {
        return exitCode == 0 && !timedOut;
    }

    /** Beschreibung ohne Zugangsdaten; Argumentwerte werden gekuerzt. */
    public String describeCommand() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commandLine.size(); i++) {
            String part = commandLine.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(part.length() > 60 ? part.substring(0, 60) + "..." : part);
        }
        return sb.toString();
    }

    public String redactedStderr() {
        return ch.so.agi.ops.security.Redactor.redact(stderr).text();
    }
}
