package ch.so.agi.ops.domain;

import java.util.List;
import java.util.Objects;

/**
 * Herkunft eines Belegs. Der Locator beschreibt, wo im Quellsystem der Inhalt stand.
 * Es werden niemals Zugangsdaten, Header oder Secretwerte uebernommen.
 *
 * @param sourcePath     Pfad/Feld innerhalb der Quelle, z. B. {@code /api/json} oder {@code spec.template.spec.containers[0].livenessProbe}
 * @param sourceLineFrom erste Zeile im Quelltext, falls bekannt (1-basiert)
 * @param sourceLineTo   letzte Zeile im Quelltext, falls bekannt (1-basiert)
 * @param sourceByteFrom erste Byteposition im Quellstrom, falls bekannt
 * @param streamingOrAggregated wahr, wenn Zaehler aggregiert oder ein Stream abgeschnitten wurde
 * @param truncationNote Klartextnotiz zu einer Truncation; leer, wenn nichts abgeschnitten wurde
 */
public record Locator(
        String sourcePath,
        Integer sourceLineFrom,
        Integer sourceLineTo,
        Long sourceByteFrom,
        boolean streamingOrAggregated,
        String truncationNote) {

    public Locator {
        Objects.requireNonNull(sourcePath, "sourcePath");
        truncationNote = truncationNote == null ? "" : truncationNote;
    }

    public static Locator of(String sourcePath) {
        return new Locator(sourcePath, null, null, null, false, "");
    }

    public static Locator lines(String sourcePath, int from, int to) {
        return new Locator(sourcePath, from, to, null, false, "");
    }
}
