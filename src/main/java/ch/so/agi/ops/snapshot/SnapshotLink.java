package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.LinkStrength;

import java.util.List;

/**
 * Beziehung zwischen zwei Systemen, zum Beispiel Build ↔ Workload/Revision ↔ Konfiguration.
 *
 * @param from         Ausgangsknoten, z. B. {@code build:apps-deploy/datenportal#42}
 * @param to           Zielknoten, z. B. {@code workload:ocp/fixture-integration/Deployment/fixture-modelfinder}
 * @param relation     Beziehungsart, z. B. {@code build-to-workload}
 * @param strength     Belegstaerke
 * @param reason       Begruendung der Einstufung
 * @param evidenceRefs tragende Evidence-IDs
 * @param limitations  Grenzen der Zuordnung
 */
public record SnapshotLink(
        String from,
        String to,
        String relation,
        LinkStrength strength,
        String reason,
        List<String> evidenceRefs,
        List<String> limitations) {

    public SnapshotLink {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("from", from);
        map.put("to", to);
        map.put("relation", relation);
        map.put("strength", strength.name().toLowerCase(java.util.Locale.ROOT));
        map.put("reason", reason);
        map.put("evidenceRefs", evidenceRefs);
        if (!limitations.isEmpty()) {
            map.put("limitations", limitations);
        }
        return map;
    }
}
