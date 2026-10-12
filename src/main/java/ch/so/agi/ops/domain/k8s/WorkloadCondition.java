package ch.so.agi.ops.domain.k8s;

import java.time.Instant;

/**
 * Workload-Condition. {@code ProgressDeadlineExceeded}, ein laufender und ein abgeschlossener
 * Rollout duerfen nicht gleich behandelt werden.
 *
 * @param type               z. B. Available, Progressing, ReplicaFailure
 * @param status             True/False/Unknown
 * @param reason             Reason der Condition
 * @param message            Meldung der Condition, redigiert
 * @param lastTransitionTime Zeitbezug der letzten Zustandsaenderung
 * @param lastUpdateTime     Zeitbezug der letzten Aktualisierung
 */
public record WorkloadCondition(
        String type,
        String status,
        String reason,
        String message,
        Instant lastTransitionTime,
        Instant lastUpdateTime) {

    public boolean isTrue() {
        return "True".equalsIgnoreCase(status);
    }
}
