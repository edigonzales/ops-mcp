package ch.so.agi.ops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Rohbeleg eines Adapters, noch ohne gespeicherte Datei und ohne Hash. Der Snapshot-Store
 * redigiert, nummeriert und persistiert daraus unveraenderliche Evidence-Dateien.
 *
 * <p>{@code observedAt} ist der fachliche Zeitbezug, soweit er aus der Quelle hervorgeht.
 * Es ist ausdruecklich <em>nicht</em> die Erfassungszeit ({@code collectedAt}).</p>
 *
 * @param id           vom Collector vergebene, innerhalb des Snapshots eindeutige Kennung
 * @param type         Evidence-Art
 * @param sourceId     Kennung der Quelle
 * @param object       betroffenes Betriebsobjekt, soweit bekannt
 * @param observedAt   fachlicher Zeitbezug oder {@code null}
 * @param locator      Herkunft im Quellsystem
 * @param contentLines bereinigter Inhalt; eine Zeile pro Eintrag, ohne Zeilennummernpraefix
 * @param structured    strukturierte Projektion derselben Evidence, soweit vorhanden
 * @param description   kurze Inhaltsbeschreibung fuer Toolausgaben
 * @param notes        Hinweise, insbesondere zu Aggregation, Luecken und Truncation
 */
public record RawEvidence(
        String id,
        EvidenceType type,
        String sourceId,
        ObjectRef object,
        Instant observedAt,
        Locator locator,
        List<String> contentLines,
        Object structured,
        String description,
        List<String> notes) {

    public RawEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(locator, "locator");
        contentLines = contentLines == null ? List.of() : List.copyOf(contentLines);
        description = description == null ? "" : description;
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static RawEvidence ofLines(String id, EvidenceType type, String sourceId, ObjectRef object,
            Instant observedAt, Locator locator, List<String> contentLines) {
        return new RawEvidence(id, type, sourceId, object, observedAt, locator, contentLines, null, "", List.of());
    }

    public RawEvidence withNotes(List<String> newNotes) {
        return new RawEvidence(id, type, sourceId, object, observedAt, locator, contentLines, structured,
                description, newNotes);
    }

    public RawEvidence withDescription(String newDescription) {
        return new RawEvidence(id, type, sourceId, object, observedAt, locator, contentLines, structured,
                newDescription, notes);
    }
}
