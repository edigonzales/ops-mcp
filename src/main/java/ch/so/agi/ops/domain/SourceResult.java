package ch.so.agi.ops.domain;

import java.util.Objects;

/**
 * Ergebnis einer einzelnen Quelle. Kein Adapter liefert eine nackte Liste: Status,
 * Erfassungszeit, Hinweise und redigierte Fehler gehoeren zum Ergebnis.
 *
 * @param sourceId    Kennung der Quelle (Cluster, Jenkins-Instanz, Repository, Zielpfad)
 * @param status      Erfassungsstatus
 * @param data        fachliche Nutzlast; bei Fehlern ein leerer, aber nie {@code null} Wert
 * @param collectedAt Erfassungszeit
 * @param notes       Hinweise fachlicher Art, ohne Zugangsdaten
 * @param errors      strukturierte, redigierte Fehler
 * @param <T>         Nutzlasttyp der Quelle
 */
public record SourceResult<T>(
        String sourceId,
        SourceStatus status,
        T data,
        java.time.Instant collectedAt,
        java.util.List<String> notes,
        java.util.List<OpsError> errors) {

    public SourceResult {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(data, "data");
        notes = notes == null ? java.util.List.of() : java.util.List.copyOf(notes);
        errors = errors == null ? java.util.List.of() : java.util.List.copyOf(errors);
    }

    public static <T> SourceResult<T> collected(String sourceId, T data, java.time.Instant at) {
        return new SourceResult<>(sourceId, SourceStatus.COLLECTED, data, at, java.util.List.of(), java.util.List.of());
    }

    public static <T> SourceResult<T> of(String sourceId, SourceStatus status, T data, java.time.Instant at,
            java.util.List<String> notes, java.util.List<OpsError> errors) {
        return new SourceResult<>(sourceId, status, data, at, notes, errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
