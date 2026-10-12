package ch.so.agi.ops.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Fixtureeinstellungen. Nur im Modus {@code fixture} zulaessig. Die Fixtureadapter verwenden
 * dieselben Domaenenmodelle und Parser wie die Live-Adapter; das Szenario enthaelt
 * Rohantworten und Fake-Transporte, nicht die gewuenschte Enddiagnose.
 *
 * @param root               Wurzelverzeichnis der synthetischen Szenarien
 * @param scenario           Standard-Szenario (z. B. {@code t01-liveness-startup})
 * @param clock              Zeitquelle: {@code fixed} oder {@code system}
 * @param fixedNow           fester Zeitpunkt bei {@code clock: fixed}
 */
public record FixtureSettings(Path root, String scenario, String clock, String fixedNow) {

    public static final String CLOCK_FIXED = "fixed";
    public static final String CLOCK_SYSTEM = "system";

    public boolean isFixedClock() {
        return CLOCK_FIXED.equals(clock);
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("rootConfigured", root != null);
        map.put("scenario", scenario);
        map.put("clock", clock);
        map.put("fixedNow", fixedNow);
        map.put("synthetic", true);
        return map;
    }
}
