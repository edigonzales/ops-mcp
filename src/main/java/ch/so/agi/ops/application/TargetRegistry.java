package ch.so.agi.ops.application;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.application.port.ResolvedTarget;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kurzlebiger Sessionzustand fuer Target-Handles.
 *
 * <p>Handles sind 30 Minuten gueltig und gelten nur innerhalb des Serverprozesses. Die daraus
 * erzeugten Snapshots sind unabhaengig davon dauerhaft ueber ihre ID lesbar. Ein unbekannter
 * oder abgelaufener Handle muss neu aufgeloest werden; Scope und Build werden nie aus
 * nachtraeglich uebergebenen Werten uebernommen.</p>
 */
public final class TargetRegistry {

    public static final int MAX_ENTRIES = 32;

    private final Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
    private final ClockProvider clock;

    public TargetRegistry(ClockProvider clock) {
        this.clock = clock;
    }

    public synchronized void register(ResolvedTarget target) {
        targets.put(target.targetId(), target);
        if (targets.size() > MAX_ENTRIES) {
            var iterator = targets.entrySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    /** Liefert das Ziel oder wirft {@code TARGET_EXPIRED}, wenn es fehlt oder abgelaufen ist. */
    public synchronized ResolvedTarget require(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new TargetResolutionException(ErrorCode.TARGET_EXPIRED,
                    "Es wurde kein targetId uebergeben. Das Ziel muss zuerst mit ops_resolve_target "
                            + "aufgeloest werden.");
        }
        ResolvedTarget target = targets.get(targetId);
        if (target == null) {
            throw new TargetResolutionException(ErrorCode.TARGET_EXPIRED,
                    "Unbekanntes targetId. Das Ziel muss erneut aufgeloest werden.");
        }
        Instant now = clock.now();
        if (target.isExpired(now)) {
            targets.remove(targetId);
            throw new TargetResolutionException(ErrorCode.TARGET_EXPIRED,
                    "Das targetId ist abgelaufen. Das Ziel muss erneut aufgeloest werden.");
        }
        return target;
    }

    public synchronized int size() {
        return targets.size();
    }

    /** Erzeugt ein neues Budget fuer eine weitere Erfassung desselben Ziels. */
    public static ResolvedTarget withFreshBudget(ResolvedTarget target, ClockProvider clock) {
        var limits = target.config().limits();
        return new ResolvedTarget(target.targetId(), target.config(), target.dataMode(), target.applicationId(),
                target.environment(), target.definition(), target.buildRef(), target.credential(),
                target.resolvedAt(), target.expiresAt(),
                ch.so.agi.ops.domain.CollectionBudget.of(
                        java.time.Duration.ofSeconds(limits.collectTimeoutSeconds()),
                        clock.now(),
                        limits.maxRequests(),
                        limits.maxPods(),
                        limits.maxEvents(),
                        limits.maxTotalInputBytes(),
                        limits.maxEvidenceResponseBytes()),
                target.warnings(), target.scenarioId());
    }
}
