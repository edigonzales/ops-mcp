package ch.so.agi.ops.adapter.jenkins;

import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Textverarbeitung des Jenkins-Console-Logs: tolerantes UTF-8-Dekodieren, Zeilenschnitt,
 * Zeitstempelerkennung und mechanische Fehleranker.
 *
 * <p>Ein Log ohne Zeitstempel bekommt Zeilenreferenzen, keine erfundenen Uhrzeiten. Ist die
 * Nummer der ersten gelieferten Zeile nicht bestimmbar (nur ein Ausschnitt aus der Logmitte
 * oder dem Logende gelesen), wird der Anker mit {@code -1} gekennzeichnet statt eine Nummer zu
 * erfinden.</p>
 */
final class ConsoleLogSupport {

    /** Numerus der Ankererkennung: reine Orientierung, kein Kausalitaetsbeweis. */
    static final String NO_LINE_NUMBER = "Zeilennummer nicht bestimmbar";

    private static final List<String> MARKERS = List.of("ERROR", "FAILURE", "Timeout", "Exception", "error:");

    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<!\\d)(?:\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}|\\d{2}:\\d{2}:\\d{2})(?!\\d)");

    private ConsoleLogSupport() {
    }

    /** Dekodiert UTF-8 ersetzend; ungueltige Sequenzen brechen die Auswertung nicht ab. */
    static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Zerlegt einen Text in Zeilen. Eine abschliessende Zeilenendezeichenkette erzeugt keine
     * leere Schlusszeile; eine unvollstaendige letzte Zeile bleibt erhalten.
     */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(stripCr(text.substring(start, i)));
                start = i + 1;
            } else if (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(stripCr(text.substring(start)));
        }
        return lines;
    }

    static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** Erster erkannter Marker einer Zeile oder {@code null}. */
    static String marker(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (upper.contains(marker.toUpperCase(Locale.ROOT))) {
                return marker;
            }
        }
        return null;
    }

    /**
     * Mechanische Fehleranker der gelieferten Zeilen.
     *
     * @param firstLineNumber 1-basierte Nummer der ersten Zeile; {@code -1}, wenn sie nicht
     *                        bestimmbar ist
     */
    static List<ConsoleLogEvidence.ErrorAnchor> anchors(List<String> lines, int firstLineNumber) {
        List<ConsoleLogEvidence.ErrorAnchor> anchors = new ArrayList<>();
        if (lines == null) {
            return anchors;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String marker = marker(line);
            if (marker != null) {
                int number = firstLineNumber < 0 ? -1 : firstLineNumber + i;
                anchors.add(new ConsoleLogEvidence.ErrorAnchor(number, line, marker));
            }
        }
        return anchors;
    }

    /** true, wenn mindestens eine gelieferte Zeile einen echten Zeitstempel traegt. */
    static boolean hasTimestamps(List<String> lines) {
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            if (line != null && TIMESTAMP.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
