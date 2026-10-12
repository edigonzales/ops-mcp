package ch.so.agi.ops.application;

import java.time.Clock;

/**
 * Zeitquelle des Auftrags. Fachliche Zeitbezuege stammen aus den Quellen; diese Uhr liefert
 * ausschliesslich Erfassungszeiten. Im Fixturebetrieb ist sie fest, damit Abläufe
 * reproduzierbar bleiben.
 */
public final class ClockProvider {

    private final Clock clock;

    private ClockProvider(Clock clock) {
        this.clock = clock;
    }

    public static ClockProvider fixed(java.time.Instant instant) {
        return new ClockProvider(Clock.fixed(instant, java.time.ZoneOffset.UTC));
    }

    public static ClockProvider system() {
        return new ClockProvider(Clock.systemUTC());
    }

    public Clock clock() {
        return clock;
    }

    public java.time.Instant now() {
        return clock.instant();
    }

    /** Zeitzonenanzeige ist Darstellungslogik und gehoert nicht in die Speicherung. */
    public static String toRfc3339(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
