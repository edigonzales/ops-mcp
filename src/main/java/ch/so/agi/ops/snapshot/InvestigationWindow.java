package ch.so.agi.ops.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Zeitfenster der Untersuchung und seine Herleitung.
 *
 * <p>Das Standardfenster richtet sich an einem konkreten Jenkins-Lauf aus: Start minus zwei
 * Minuten bis Ende plus zwei Minuten. Bei laufendem Build bildet die Erfassungszeit die
 * vorlaeufige obere Grenze. Ohne Buildzeit wird kein historisches Fenster erfunden.</p>
 *
 * @param from      Fensterbeginn oder {@code null}
 * @param to        Fensterende oder {@code null}
 * @param timeBasis Herleitung des Fensters
 * @param derivation Klartextherleitung
 */
public record InvestigationWindow(Instant from, Instant to, TimeBasis timeBasis, String derivation) {

    public enum TimeBasis {
        /** Aus Startzeit und Ende plus Reserve abgeleitet. */
        BUILD_START_AND_END("build-start-and-end"),
        /** Aus der Startzeit und der Erfassungszeit als vorlaeufiger Obergrenze. */
        BUILD_START_AND_COLLECTION("build-start-and-collection"),
        /** Keine Buildzeit bekannt; das Fenster ist die aktuelle Erfassung. */
        UNKNOWN("unknown");

        private final String wireName;

        TimeBasis(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public static final java.time.Duration PADDING = java.time.Duration.ofMinutes(2);

    public InvestigationWindow {
        Objects.requireNonNull(timeBasis, "timeBasis");
        derivation = derivation == null ? "" : derivation;
    }

    public static InvestigationWindow unknown(Instant collectedAt) {
        return new InvestigationWindow(collectedAt, collectedAt, TimeBasis.UNKNOWN,
                "Keine Buildzeit bekannt; das Fenster entspricht der aktuellen Erfassung und ist keine Historie.");
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("from", from == null ? null : from.toString());
        map.put("to", to == null ? null : to.toString());
        map.put("timeBasis", timeBasis.wireName());
        map.put("derivation", derivation);
        return map;
    }

    public List<Instant> boundsAsList() {
        return from == null || to == null ? List.of() : List.of(from, to);
    }
}
