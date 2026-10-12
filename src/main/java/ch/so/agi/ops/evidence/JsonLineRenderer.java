package ch.so.agi.ops.evidence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stabile, zeilennummerierte Textdarstellung strukturierter Daten.
 *
 * <p>JSON-Projektionen werden in einer deterministischen Form ausgegeben: Objektschluessel
 * alphabetisch sortiert, zwei Leerzeichen Einrueckung, ein Wert pro Zeile. Damit haben
 * Zeilenverweise im Bericht denselben Charakter wie in einem Logausschnitt und bleiben bei
 * erneutem Lesen stabil.</p>
 */
public final class JsonLineRenderer {

    private final JsonMapper mapper;

    public JsonLineRenderer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Rendert einen beliebigen Wert als zeilennummerierte JSON-Darstellung. */
    public String render(Object value) {
        JsonNode node = mapper.valueToTree(value);
        List<String> lines = new ArrayList<>();
        write(node, 0, null, lines);
        return String.join("\n", lines);
    }

    /** Anzahl der Zeilen, die {@link #render(Object)} erzeugen wuerde. */
    public int lineCount(Object value) {
        return render(value).split("\n", -1).length;
    }

    private void write(JsonNode node, int indent, String keyPrefix, List<String> lines) {
        String pad = "  ".repeat(indent);
        if (node == null || node.isNull()) {
            lines.add(pad + keyPrefix + "null");
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            if (sorted.isEmpty()) {
                lines.add(pad + keyPrefix + "{}");
                return;
            }
            if (keyPrefix != null) {
                lines.add(pad + keyPrefix.trim());
            }
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                write(entry.getValue(), indent + (keyPrefix == null ? 0 : 1), entry.getKey() + ": ", lines);
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                lines.add(pad + keyPrefix + "[]");
                return;
            }
            if (keyPrefix != null) {
                lines.add(pad + keyPrefix.trim());
            }
            int index = 0;
            for (JsonNode element : node) {
                write(element, indent + (keyPrefix == null ? 0 : 1), "[" + index + "] ", lines);
                index++;
            }
            return;
        }
        lines.add(pad + keyPrefix + scalar(node));
    }

    private String scalar(JsonNode node) {
        if (node.isString()) {
            return quoteIfNeeded(node.asString());
        }
        return node.toString();
    }

    private String quoteIfNeeded(String value) {
        String cleaned = value.replace("\n", "\\n").replace("\r", "\\r");
        if (cleaned.isEmpty()) {
            return "\"\"";
        }
        boolean plain = cleaned.chars().allMatch(c -> Character.isLetterOrDigit(c) || " ._-/:@#+".indexOf(c) >= 0);
        return plain ? cleaned : mapper.writeValueAsString(cleaned);
    }

    /** Erzeugt aus einer Map eine deterministische Objektknotenstruktur. */
    public static ObjectNode objectNode(JsonMapper mapper, Map<String, ?> values) {
        ObjectNode node = mapper.createObjectNode();
        new TreeMap<>(values).forEach((key, value) -> node.set(key, mapper.valueToTree(value)));
        return node;
    }

    /** Erzeugt aus einer Liste eine Arrayknotenstruktur. */
    public static ArrayNode arrayNode(JsonMapper mapper, List<?> values) {
        ArrayNode node = mapper.createArrayNode();
        values.forEach(value -> node.add(mapper.valueToTree(value)));
        return node;
    }
}
