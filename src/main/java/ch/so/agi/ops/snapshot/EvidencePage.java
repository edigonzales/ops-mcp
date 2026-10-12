package ch.so.agi.ops.snapshot;

import java.util.List;

/**
 * Begrenzter Ausschnitt einer Evidence-Datei. Zeilennummern sind stabil und beziehen sich
 * auf die gespeicherte, bereits bereinigte Darstellung; eine dokumentierte Zeilenabbildung
 * erlaubt den Rueckschluss auf den Quelltext.
 *
 * @param evidenceId      Evidence-ID
 * @param type            Evidence-Art
 * @param sourceId        Kennung der Quelle
 * @param lines           ausgelieferte Zeilen ohne Nummernpräfix
 * @param startLine       1-basierte Nummer der ersten Zeile
 * @param endLine         1-basierte Nummer der letzten ausgelieferten Zeile
 * @param totalLineCount  Gesamtzahl der Zeilen der Evidence
 * @param hasMore         true, wenn weitere Zeilen vorhanden sind
 * @param nextStartLine   naechste Startzeile oder {@code null}
 * @param truncatedLine   true, wenn eine ueberlange Einzelzeile abgeschnitten wurde
 * @param byteBudgetHit   true, wenn das Bytebudget die Antwort begrenzt hat
 * @param notes           Erfassungsluecken und Hinweise
 */
public record EvidencePage(
        String evidenceId,
        ch.so.agi.ops.domain.EvidenceType type,
        String sourceId,
        List<String> lines,
        int startLine,
        int endLine,
        int totalLineCount,
        boolean hasMore,
        Integer nextStartLine,
        boolean truncatedLine,
        boolean byteBudgetHit,
        List<String> notes) {

    public EvidencePage {
        lines = lines == null ? List.of() : List.copyOf(lines);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Rendert die Seite mit Zeilennummern, wie sie ein Agent lesen soll. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(String.format("%6d\t%s", startLine + i, lines.get(i)));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("evidenceId", evidenceId);
        map.put("type", type.wireName());
        map.put("sourceId", sourceId);
        map.put("text", render());
        map.put("startLine", startLine);
        map.put("endLine", endLine);
        map.put("totalLineCount", totalLineCount);
        map.put("hasMore", hasMore);
        map.put("nextStartLine", nextStartLine);
        map.put("truncatedLine", truncatedLine);
        map.put("byteBudgetHit", byteBudgetHit);
        if (!notes.isEmpty()) {
            map.put("gaps", notes);
        }
        return map;
    }
}
