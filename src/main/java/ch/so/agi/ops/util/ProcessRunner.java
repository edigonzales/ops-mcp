package ch.so.agi.ops.util;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Startet externe, freigegebene Programme. Befehle werden ausschliesslich als Argumentlisten
 * uebergeben, nie als Shelltext. Es findet keine Shellinterpretation statt.
 *
 * <p>Jeder Aufruf hat ein Timeout, ein Ausgabebudget und wird bei Zeitueberschreitung beendet.
 * Es gibt keine unbegrenzten Watches oder Wiederholungen.</p>
 */
public interface ProcessRunner {

    /**
     * Fuehrt ein Programm aus.
     *
     * @param executable absoluter Pfad oder Name des Programms
     * @param arguments  Argumente in fester Reihenfolge; keine Shellmetazeicheninterpretation
     * @param workingDir Arbeitsverzeichnis oder {@code null}
     * @param extraEnv   zusaetzliche Umgebungsvariablen; {@code null} bedeutet keine Aenderung
     * @param timeout    maximale Laufzeit
     * @param maxOutputBytes maximale Groesse von stdout und stderr zusammen
     * @return Ergebnis des Aufrufs
     */
    ExecResult exec(String executable, List<String> arguments, java.nio.file.Path workingDir,
            Map<String, String> extraEnv, Duration timeout, long maxOutputBytes);

    /** Fuehrt ein Programm aus und wirft bei einem Abbruch durch das Budget eine Exception. */
    default ExecResult execOrThrow(String executable, List<String> arguments, java.nio.file.Path workingDir,
            Map<String, String> extraEnv, Duration timeout, long maxOutputBytes) {
        ExecResult result = exec(executable, arguments, workingDir, extraEnv, timeout, maxOutputBytes);
        if (result.timedOut()) {
            throw new ProcessExecutionException("Zeitueberschreitung beim Aufruf: " + executable, result);
        }
        return result;
    }
}
