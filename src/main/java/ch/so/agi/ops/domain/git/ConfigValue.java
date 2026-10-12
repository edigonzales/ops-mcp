package ch.so.agi.ops.domain.git;

import java.util.Objects;

/**
 * Einzelner aufgeloester Konfigurationswert mit vollstaendiger Herkunft.
 *
 * <p>In V1 werden nur einfache, explizite Parameterzuweisungen und einfache
 * {@code ${NAME}}-Referenzen auf erlaubte Parameter aufgeloest. Keine Shellausdruecke,
 * Substitutionen, Default-/Command-Expansionen, Helm-, Groovy- oder Jenkinsfile-Ausfuehrung.
 * Nicht aufloesbare oder widerspruechliche Werte bleiben offen.</p>
 *
 * @param pointer       JSON-/YAML-Pointer im Template, z. B. {@code /spec/template/spec/containers/0/livenessProbe/initialDelaySeconds}
 * @param value         aufgeloester Wert als Text oder {@code null}, wenn nicht aufloesbar
 * @param resolution    ART der Aufloesung
 * @param templateFile  relative Pfadangabe der Templatedatei
 * @param templateLine  1-basierte Zeilennummer im Template, soweit ermittelbar
 * @param parameterFile relative Pfadangabe der Parameterdatei, falls der Wert daraus stammt
 * @param parameterLine 1-basierte Zeilennummer in der Parameterdatei, falls bekannt
 * @param parameterName Parametername, falls der Wert aus einer {@code ${NAME}}-Referenz stammt
 * @param sourceText    bereinigter Quelltext der Zeile, soweit hilfreich und unkritisch
 * @param note          Klartextnotiz, insbesondere bei nicht aufloesbaren Werten
 */
public record ConfigValue(
        String pointer,
        String value,
        Resolution resolution,
        String templateFile,
        Integer templateLine,
        String parameterFile,
        Integer parameterLine,
        String parameterName,
        String sourceText,
        String note) {

    public enum Resolution {
        /** Direkt im Template als Literal gefunden. */
        TEMPLATE_LITERAL,
        /** Direkt in der Parameterdatei als Zuweisung gefunden. */
        PARAMETER_ASSIGNMENT,
        /** Einfache {@code ${NAME}}-Referenz auf einen erlaubten Parameter. */
        SUBSTITUTED,
        /** Der Wert konnte nicht aufgeloest werden; er bleibt ausdruecklich offen. */
        UNRESOLVED,
        /** Widerspruechliche Angaben aus zwei Quellen; der Wert bleibt offen. */
        CONFLICTING
    }

    public ConfigValue {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(resolution, "resolution");
        sourceText = sourceText == null ? "" : sourceText;
        note = note == null ? "" : note;
    }

    public boolean isResolved() {
        return value != null && (resolution == Resolution.TEMPLATE_LITERAL
                || resolution == Resolution.PARAMETER_ASSIGNMENT
                || resolution == Resolution.SUBSTITUTED);
    }

    /** Kompakte Herkunftsangabe fuer Toolausgaben. */
    public String provenance() {
        StringBuilder sb = new StringBuilder();
        sb.append(templateFile == null ? "?" : templateFile);
        if (templateLine != null) {
            sb.append(':').append(templateLine);
        }
        if (parameterFile != null) {
            sb.append(" + ").append(parameterFile);
            if (parameterLine != null) {
                sb.append(':').append(parameterLine);
            }
            if (parameterName != null) {
                sb.append(" (").append(parameterName).append(')');
            }
        }
        return sb.toString();
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("pointer", pointer);
        map.put("value", value);
        map.put("resolution", resolution.name());
        map.put("templateFile", templateFile);
        map.put("templateLine", templateLine);
        map.put("parameterFile", parameterFile);
        map.put("parameterLine", parameterLine);
        map.put("parameterName", parameterName);
        if (!note.isBlank()) {
            map.put("note", note);
        }
        return map;
    }
}
