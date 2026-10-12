package ch.so.agi.ops.domain.jenkins;

import java.util.Map;

/**
 * Ausgewaehlter Jenkins-Buildparameter. Nur explizit erlaubte Parameternamen werden
 * uebernommen; alles andere wird nicht in den Snapshot geschrieben.
 *
 * @param name       Parametername
 * @param value      Wert, redigiert
 * @param redacted   true, wenn der Wert wegen Secretverdacht redigiert wurde
 */
public record BuildParameter(String name, String value, boolean redacted) {

    public static Map<String, BuildParameter> byName(Iterable<BuildParameter> parameters) {
        Map<String, BuildParameter> map = new java.util.LinkedHashMap<>();
        for (BuildParameter parameter : parameters) {
            map.put(parameter.name(), parameter);
        }
        return map;
    }
}
