package ch.so.agi.ops.domain.jenkins;

import java.util.List;
import java.util.Objects;

/**
 * Textbeleg eines Console-Logs. Kleine Logs werden vollstaendig gelesen, grosse
 * streaming- und budgetbegrenzt.
 *
 * <p>Ein Console-Log ohne Zeitstempel bekommt Zeilenreferenzen, keine erfundenen Uhrzeiten.
 * Wird das Lesebudget vor dem Ende erreicht, bleibt das ausdruecklich als Truncation
 * gekennzeichnet: es wird nicht behauptet, das Buildende untersucht zu haben.</p>
 *
 * @param ref                   Buildreferenz
 * @param lines                 bereinigte Zeilen; ohne Zeilennummernpraefix
 * @param firstLineNumber       1-basierte Zeilennummer der ersten gelieferten Zeile im Quelllog
 * @param totalBytesAvailable   Gesamtgroesse des Logs, soweit aus dem HTTP-Header bekannt
 * @param bytesRead             tatsaechlich gelesene Bytes
 * @param truncated             true, wenn nicht das gesamte Log gelesen wurde
 * @param truncatedReason       Klartextgrund der Truncation
 * @param timestampsAvailable   true, wenn die Zeilen echte Zeitstempel tragen
 * @param headRequestedFromByte Byteposition des Kopfausschnitts, falls ein Kopfausschnitt gelesen wurde
 * @param tailRequestedFromByte Byteposition des Endausschnitts, falls ein Endausschnitt gelesen wurde
 * @param errorAnchors          erkannte Fehlerzeilen mit Zeilennummer, rein mechanisch
 */
public record ConsoleLogEvidence(
        BuildRef ref,
        List<String> lines,
        int firstLineNumber,
        Long totalBytesAvailable,
        long bytesRead,
        boolean truncated,
        String truncatedReason,
        boolean timestampsAvailable,
        Long headRequestedFromByte,
        Long tailRequestedFromByte,
        List<ErrorAnchor> errorAnchors) {

    public ConsoleLogEvidence {
        Objects.requireNonNull(ref, "ref");
        lines = lines == null ? List.of() : List.copyOf(lines);
        truncatedReason = truncatedReason == null ? "" : truncatedReason;
        errorAnchors = errorAnchors == null ? List.of() : List.copyOf(errorAnchors);
    }

    /**
     * Mechanisch erkannter Fehleranker im Console-Log. Die Erkennung ist eine Orientierung
     * fuer den Agenten, kein Kausalitaetsbeweis.
     *
     * @param lineNumber Zeilennummer im Quelllog (1-basiert)
     * @param line       bereinigter Zeileninhalt
     * @param marker     erkannter Marker, z. B. {@code ERROR} oder {@code Timeout}
     */
    public record ErrorAnchor(int lineNumber, String line, String marker) {
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("buildNumber", ref.buildNumber());
        map.put("lineCount", lines.size());
        map.put("firstLineNumber", firstLineNumber);
        map.put("totalBytesAvailable", totalBytesAvailable);
        map.put("bytesRead", bytesRead);
        map.put("truncated", truncated);
        map.put("timestampsAvailable", timestampsAvailable);
        map.put("errorAnchorCount", errorAnchors.size());
        return map;
    }
}
