package ch.so.agi.ops.snapshot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pfadsichere, zufaellig erzeugte Snapshot-ID. Sie ist kein vom Modell editierbares
 * Scopeobjekt und enthaelt keine Pfadbestandteile.
 */
public record SnapshotId(String value) {

    private static final Pattern VALID = Pattern.compile("[0-9]{8}T[0-9]{6}Z-[a-z0-9]{8}");

    public SnapshotId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Unzulaessige Snapshot-ID: " + value);
        }
    }

    public static SnapshotId of(String value) {
        return new SnapshotId(value);
    }

    /** Erzeugt eine neue ID aus einem festen Zeitpunkt und einem Zufallsanteil. */
    public static SnapshotId generate(java.time.Instant at, java.util.random.RandomGenerator random) {
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(at);
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append((char) ('a' + random.nextInt(26)));
        }
        return new SnapshotId(timestamp + "-" + suffix);
    }

    @Override
    public String toString() {
        return value;
    }
}
