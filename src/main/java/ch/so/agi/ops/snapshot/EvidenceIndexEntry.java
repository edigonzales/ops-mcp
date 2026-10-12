package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.ObjectRef;

import java.time.Instant;

/**
 * Eintrag im Evidence-Index des Manifests.
 *
 * @param id          Evidence-ID, z. B. {@code EV-0004}
 * @param type        Evidence-Art
 * @param sourceId    Kennung der Quelle
 * @param object      betroffenes Betriebsobjekt, soweit bekannt
 * @param observedAt  fachlicher Zeitbezug, soweit bekannt
 * @param collectedAt Erfassungszeit
 * @param path        relativer Pfad im Snapshotverzeichnis
 * @param sha256      Hash des gespeicherten, bereits bereinigten Inhalts
 * @param lineCount   Zeilenanzahl der gespeicherten Darstellung
 * @param byteSize    Groesse der gespeicherten Darstellung
 * @param locator     Herkunft im Quellsystem
 * @param dataMode    fixture oder live
 * @param derivedFrom Evidence-IDs, aus denen diese Evidence abgeleitet wurde
 * @param description kurze Inhaltsbeschreibung, z. B. "Jenkins-Buildmetadaten"
 * @param notes       Hinweise, insbesondere Aggregation, Luecken, Redaktion
 */
public record EvidenceIndexEntry(
        String id,
        EvidenceType type,
        String sourceId,
        ObjectRef object,
        Instant observedAt,
        Instant collectedAt,
        String path,
        String sha256,
        int lineCount,
        long byteSize,
        ch.so.agi.ops.domain.Locator locator,
        DataMode dataMode,
        java.util.List<String> derivedFrom,
        String description,
        java.util.List<String> notes) {

    public EvidenceIndexEntry {
        derivedFrom = derivedFrom == null ? java.util.List.of() : java.util.List.copyOf(derivedFrom);
        description = description == null ? "" : description;
        notes = notes == null ? java.util.List.of() : java.util.List.copyOf(notes);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        // evidenceId ist der stabile Name in allen Werkzeug- und CLI-Ausgaben; id bleibt als
        // Manifestform erhalten.
        map.put("evidenceId", id);
        map.put("id", id);
        map.put("type", type.wireName());
        map.put("sourceId", sourceId);
        if (object != null) {
            java.util.Map<String, Object> obj = new java.util.LinkedHashMap<>();
            obj.put("kind", object.kind());
            obj.put("namespace", object.namespace());
            obj.put("name", object.name());
            if (object.uid() != null) {
                obj.put("uid", object.uid());
            }
            map.put("object", obj);
        }
        map.put("observedAt", observedAt == null ? null : observedAt.toString());
        map.put("collectedAt", collectedAt == null ? null : collectedAt.toString());
        java.util.Map<String, Object> loc = new java.util.LinkedHashMap<>();
        loc.put("path", path);
        loc.put("lineCount", lineCount);
        loc.put("byteSize", byteSize);
        map.put("locator", loc);
        map.put("provenance", locator == null ? null : locator.sourcePath());
        map.put("sha256", sha256);
        map.put("byteSize", byteSize);
        map.put("lineCount", lineCount);
        map.put("dataMode", dataMode.wireName());
        if (!description.isBlank()) {
            map.put("description", description);
        }
        if (!derivedFrom.isEmpty()) {
            map.put("derivedFrom", derivedFrom);
        }
        if (!notes.isEmpty()) {
            map.put("notes", notes);
        }
        return map;
    }
}
