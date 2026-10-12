package ch.so.agi.ops.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versionsangaben fuer das Manifest. Es werden nur die tatsaechlich verwendeten Werkzeuge
 * eingetragen, keine vollstaendigen Systemdumps.
 */
public final class VersionInfo {

    private final Map<String, String> values = new LinkedHashMap<>();

    public VersionInfo(String applicationVersion) {
        values.put("java", System.getProperty("java.version", "unbekannt"));
        values.put("javaVendor", System.getProperty("java.vendor", "unbekannt"));
        values.put("opsAgent", applicationVersion == null ? "unbekannt" : applicationVersion);
        values.put("os", System.getProperty("os.name", "unbekannt") + " " + System.getProperty("os.version", ""));
    }

    public VersionInfo put(String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            values.put(key, value);
        }
        return this;
    }

    public Map<String, String> toMap() {
        return Map.copyOf(values);
    }
}
