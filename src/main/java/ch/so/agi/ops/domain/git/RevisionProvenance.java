package ch.so.agi.ops.domain.git;

import java.util.Objects;

/**
 * Konfigurationsrevision eines untersuchten Builds.
 *
 * <p>Der Collector darf einen vorgegebenen Commit nicht allein deshalb als tatsaechlich
 * deployed bezeichnen, weil dessen Datei lesbar ist. Kein Agent-Tool darf seine eigene
 * Herkunftsstufe als {@code VERIFIED} festlegen.</p>
 *
 * @param repositoryId Kennung des Repositories, z. B. {@code sogis/openshift-templates}
 * @param commit       vollstaendiger Commit oder {@code null}, wenn unbekannt
 * @param shortCommit  Kurzform, nur zur Anzeige
 * @param source       Herkunft der Revision
 * @param forBuild     Buildnummer, auf die sich eine Betreiberangabe bezieht
 * @param evidenceRef  Verweis auf den Beleg (z. B. Evidence-ID oder Beschreibung), der die Zuordnung traegt
 * @param note         Klartextnotiz, insbesondere bei fehlender oder mehrdeutiger Zuordnung
 */
public record RevisionProvenance(
        String repositoryId,
        String commit,
        String shortCommit,
        Source source,
        Integer forBuild,
        String evidenceRef,
        String note) {

    public enum Source {
        /** Vollstaendiger Commit aus strukturierten Jenkins-SCM-Daten. */
        JENKINS_SCM("jenkins-scm"),
        /** Commit aus einem im Console-Log erkennbaren Checkout; mit Zeilenbeleg. */
        JENKINS_CONSOLE_CHECKOUT("jenkins-console-checkout"),
        /** Betreiberangabe; bis zur unabhaengigen Bestaetigung nicht verifiziert. */
        OPERATOR_SUPPLIED("operator-supplied"),
        /** Keine Zuordnung moeglich. */
        UNKNOWN("unknown");

        private final String wireName;

        Source(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Source fromWireName(String value) {
            for (Source source : values()) {
                if (source.wireName.equals(value)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("Unbekannte Revisionsquelle: " + value);
        }
    }

    public RevisionProvenance {
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(source, "source");
        note = note == null ? "" : note;
    }

    public boolean hasCommit() {
        return commit != null && !commit.isBlank();
    }

    /**
     * Herkunftsstufe fuer den Snapshot. Eine Betreiberangabe bleibt unverifiziert, solange
     * keine unabhaengigen Buildbelege die Zuordnung bestaetigen.
     */
    public String provenanceLevel() {
        return switch (source) {
            case JENKINS_SCM -> "jenkins_scm";
            case JENKINS_CONSOLE_CHECKOUT -> "console_checkout_unverified";
            case OPERATOR_SUPPLIED -> "operator_supplied_unverified";
            case UNKNOWN -> "unknown";
        };
    }

    public static RevisionProvenance unknown(String repositoryId, String note) {
        return new RevisionProvenance(repositoryId, null, null, Source.UNKNOWN, null, null, note);
    }

    public static RevisionProvenance operatorSupplied(String repositoryId, String commit, int forBuild) {
        String shortCommit = commit != null && commit.length() >= 8 ? commit.substring(0, 8) : commit;
        return new RevisionProvenance(repositoryId, commit, shortCommit, Source.OPERATOR_SUPPLIED, forBuild, null,
                "Betreiberangabe; keine unabhaengige Buildbestaetigung vorhanden.");
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("repositoryId", repositoryId);
        map.put("commit", commit);
        map.put("source", source.wireName());
        map.put("provenanceLevel", provenanceLevel());
        map.put("forBuild", forBuild);
        if (evidenceRef != null) {
            map.put("evidenceRef", evidenceRef);
        }
        if (!note.isBlank()) {
            map.put("note", note);
        }
        return map;
    }
}
