package ch.so.agi.ops.domain.k8s;

import ch.so.agi.ops.domain.ObjectRef;

import java.time.Instant;

/**
 * Normalisiertes Event. Deckt {@code core/v1} und {@code events.k8s.io/v1} ueber einen
 * gemeinsamen Normalisierer ab.
 *
 * <p>Aggregierte Zaehler und erste/letzte Beobachtungszeit bleiben erhalten. Daraus wird
 * <em>keine</em> sekundengenaue Ereignisliste erfunden. Wird dasselbe Event ueber ein
 * Fallbackformat erneut gelesen, ist {@code duplicateOfFormat} gesetzt und der Beleg wird
 * nicht als unabhaengiger zweiter Beweis gezaehlt.</p>
 *
 * @param involvedObject    betroffenes Objekt inklusive UID
 * @param reason            Reason
 * @param message           Meldung, redigiert
 * @param type              Normal oder Warning
 * @param reportingComponent Meldefeld (source.component bzw. reportingController)
 * @param eventTime         eventTime (events.k8s.io/v1) oder {@code null}
 * @param firstTimestamp    erste Beobachtung (core/v1 firstTimestamp)
 * @param lastTimestamp     letzte Beobachtung (core/v1 lastTimestamp)
 * @param count             aggregierter Zaehler, soweit vorhanden
 * @param eventFormat       core/v1, events.k8s.io/v1 oder unknown
 * @param duplicateOfFormat Format, aus dem dasselbe Event bereits vorlag, sonst {@code null}
 * @param observedAt        Erfassungszeit des Belegs
 * @param uid               UID des Event-Objekts selbst, soweit vorhanden
 * @param sourcePath        Herkunftspfad der Antwort
 */
public record EventSnapshot(
        ObjectRef involvedObject,
        String reason,
        String message,
        String type,
        String reportingComponent,
        Instant eventTime,
        Instant firstTimestamp,
        Instant lastTimestamp,
        Integer count,
        String eventFormat,
        String duplicateOfFormat,
        Instant observedAt,
        String uid,
        String sourcePath) {

    public static final String FORMAT_CORE = "core/v1";
    public static final String FORMAT_EVENTS_K8S_IO = "events.k8s.io/v1";
    public static final String FORMAT_UNKNOWN = "unknown";

    /** Beste verfuegbare Zeitangabe des Events, ohne sie zu erfinden. */
    public Instant bestKnownTime() {
        if (lastTimestamp != null) {
            return lastTimestamp;
        }
        if (eventTime != null) {
            return eventTime;
        }
        return firstTimestamp;
    }

    public boolean isAggregated() {
        return count != null && count > 1;
    }

    public boolean isDuplicate() {
        return duplicateOfFormat != null;
    }

    /**
     * Stabiler Schluessel zur Erkennung desselben Events aus zwei Formaten. Er enthaelt
     * bewusst keine Zeitangabe, weil Zeitstempel zwischen den Formaten abweichen koennen.
     */
    public String dedupeKey() {
        return String.join("|",
                involvedObject == null || involvedObject.uid() == null ? "" : involvedObject.uid(),
                involvedObject == null ? "" : involvedObject.kind(),
                involvedObject == null ? "" : involvedObject.name(),
                reason == null ? "" : reason,
                message == null ? "" : message,
                count == null ? "" : count.toString());
    }

    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("involvedObject", involvedObject == null ? null : involvedObject.describe());
        map.put("involvedObjectUid", involvedObject == null ? null : involvedObject.uid());
        map.put("type", type);
        map.put("reason", reason);
        map.put("count", count);
        map.put("eventTime", eventTime == null ? null : eventTime.toString());
        map.put("firstTimestamp", firstTimestamp == null ? null : firstTimestamp.toString());
        map.put("lastTimestamp", lastTimestamp == null ? null : lastTimestamp.toString());
        map.put("eventFormat", eventFormat);
        map.put("reportingComponent", reportingComponent);
        map.put("aggregated", isAggregated());
        map.put("duplicateOfFormat", duplicateOfFormat);
        map.put("message", message);
        map.put("timeBasisHint", bestKnownTime() == null
                ? "keine Zeitangabe in der Quelle"
                : "letzte Beobachtung aus dem Event");
        return map;
    }

    /** Fuer die Evidence-Darstellung: vollstaendige Zusammenfassung inklusive Meldung. */
    public java.util.Map<String, Object> toSummaryMapWithMessage() {
        return toSummaryMap();
    }
}
