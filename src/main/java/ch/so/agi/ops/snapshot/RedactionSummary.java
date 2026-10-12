package ch.so.agi.ops.snapshot;

import java.util.List;
import java.util.Map;

/**
 * Nachweis der Redaktion. Es werden <em>niemals</em> entfernte Secretwerte mitgespeichert.
 * Die Regelversion und die bekannten Einschraenkungen sind ausdruecklich Teil des Vertrags:
 * Regex-Redaktion ist keine vollstaendige Anonymisierung.
 *
 * @param ruleVersion Regelversion der Redaktion
 * @param counts      Zaehler je Regelname
 * @param totalCount  Gesamtzahl der Ersetzungen
 * @param limitations bekannte Einschraenkungen
 */
public record RedactionSummary(String ruleVersion, Map<String, Integer> counts, int totalCount,
        List<String> limitations) {

    public RedactionSummary {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static RedactionSummary empty(String ruleVersion) {
        return new RedactionSummary(ruleVersion, Map.of(), 0, defaultLimitations());
    }

    public static List<String> defaultLimitations() {
        return List.of(
                "Regex-Redaktion garantiert keine vollstaendige Anonymisierung.",
                "Nicht erkannte Secretformate koennen im Text verbleiben.",
                "Live-Logs duerfen nur an einen fuer diese Betriebsdaten freigegebenen Modellanbieter gesendet werden.");
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("ruleVersion", ruleVersion);
        map.put("counts", counts);
        map.put("totalCount", totalCount);
        map.put("limitations", limitations);
        return map;
    }
}
