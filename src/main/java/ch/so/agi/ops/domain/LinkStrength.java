package ch.so.agi.ops.domain;

/**
 * Belegstaerke der Zuordnung zwischen zwei Systemen, zum Beispiel Jenkins-Build und
 * OpenShift-Revision. Zeitnaehe und gleicher Image-Tag allein ergeben keinen exakten
 * Nachweis (siehe SPEZIFIKATION_V1.md D-02).
 */
public enum LinkStrength {
    /** Explizite Annotation, eindeutige Rollout-Revision oder passender Image-Digest. */
    EXACT,
    /** Mehrere voneinander unabhaengige Hinweise, aber kein harter Beleg. */
    PROBABLE,
    /** Mehrere plausible Kandidaten; die Zuordnung ist nicht entscheidbar. */
    AMBIGUOUS,
    /** Kein belastbarer Zusammenhang herstellbar. */
    UNLINKED;

    public boolean allowsCausalClaim() {
        return this == EXACT;
    }
}
