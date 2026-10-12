package ch.so.agi.ops.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auftragsweites Erfassungsbudget. Das Budget gilt fuer den gesamten Auftrag; einzelne Adapter
 * duerfen es nicht jeweils erneut vollstaendig verbrauchen. Nach Ablauf eines Budgets wird ein
 * partieller Snapshot mit praeziser Luecke geliefert, kein stiller Weiterlauf.
 *
 * <p>Die Klasse ist threadsicher, weil die Erfassung parallelisieren darf.</p>
 */
public final class CollectionBudget {

    private final Duration totalDuration;
    private final Instant startedAt;
    private final int maxRequests;
    private final int maxPods;
    private final int maxEvents;
    private final long maxTotalInputBytes;
    private final int maxEvidenceResponseBytes;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong inputBytes = new AtomicLong();
    private final AtomicInteger pods = new AtomicInteger();
    private final AtomicInteger events = new AtomicInteger();
    private final AtomicInteger consumedResponseBytes = new AtomicInteger();
    private final java.util.List<String> exhaustions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public CollectionBudget(Duration totalDuration, Instant startedAt, int maxRequests, int maxPods, int maxEvents,
            long maxTotalInputBytes, int maxEvidenceResponseBytes) {
        this.totalDuration = Objects.requireNonNull(totalDuration, "totalDuration");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.maxRequests = maxRequests;
        this.maxPods = maxPods;
        this.maxEvents = maxEvents;
        this.maxTotalInputBytes = maxTotalInputBytes;
        this.maxEvidenceResponseBytes = maxEvidenceResponseBytes;
    }

    public Duration totalDuration() {
        return totalDuration;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public int maxRequests() {
        return maxRequests;
    }

    public int maxPods() {
        return maxPods;
    }

    public int maxEvents() {
        return maxEvents;
    }

    public long maxTotalInputBytes() {
        return maxTotalInputBytes;
    }

    public int maxEvidenceResponseBytes() {
        return maxEvidenceResponseBytes;
    }

    public int requestsUsed() {
        return requests.get();
    }

    public long inputBytesUsed() {
        return inputBytes.get();
    }

    /** Restzeit bis zum Ablauf des Gesamtbudgets; niemals negativ. */
    public Duration remainingTime(Instant now) {
        Duration elapsed = Duration.between(startedAt, now);
        Duration remaining = totalDuration.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean timeExhausted(Instant now) {
        return remainingTime(now).isZero();
    }

    /** Effektiver Timeout eines einzelnen Aufrufs: das Minimum aus Wunsch und Restbudget. */
    public Duration effectiveTimeout(Duration requested, Instant now) {
        Duration remaining = remainingTime(now);
        return requested.compareTo(remaining) < 0 ? requested : remaining;
    }

    public boolean tryConsumeRequest() {
        while (true) {
            int current = requests.get();
            if (current >= maxRequests) {
                recordExhaustion("maxRequests=" + maxRequests + " erreicht");
                return false;
            }
            if (requests.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public boolean tryConsumePod() {
        return pods.incrementAndGet() <= maxPods;
    }

    public boolean tryConsumeEvent() {
        return events.incrementAndGet() <= maxEvents;
    }

    public void recordInputBytes(long bytes) {
        long total = inputBytes.addAndGet(Math.max(0, bytes));
        if (total > maxTotalInputBytes) {
            recordExhaustion("maxTotalInputBytes=" + maxTotalInputBytes + " erreicht");
        }
    }

    public boolean inputBudgetExceeded() {
        return inputBytes.get() > maxTotalInputBytes;
    }

    /**
     * Verbraucht Antwortbudget fuer MCP-Evidence-Antworten. Ein Agent darf durch wiederholte
     * Aufrufe keinen unbeschraenkten Zugriff auf weitere Quellen eroeffnen.
     */
    public boolean tryConsumeResponseBytes(int bytes) {
        int total = consumedResponseBytes.addAndGet(Math.max(0, bytes));
        if (total > maxEvidenceResponseBytes) {
            recordExhaustion("maxEvidenceResponseBytes=" + maxEvidenceResponseBytes + " erreicht");
            return false;
        }
        return true;
    }

    public int responseBytesUsed() {
        return consumedResponseBytes.get();
    }

    public void recordExhaustion(String note) {
        synchronized (exhaustions) {
            if (!exhaustions.contains(note)) {
                exhaustions.add(note);
            }
        }
    }

    public java.util.List<String> exhaustions() {
        synchronized (exhaustions) {
            return java.util.List.copyOf(exhaustions);
        }
    }

    public boolean anyExhaustion() {
        return !exhaustions.isEmpty();
    }

    /**
     * Erzeugt das auftragsweite Budget aus den validierten Grenzwerten der Konfiguration.
     * Die Zuordnung erfolgt im Application Service, damit das Domaenenmodell nicht von der
     * Konfigurationsschicht abhaengt.
     */
    public static CollectionBudget of(Duration totalDuration, Instant startedAt, int maxRequests, int maxPods,
            int maxEvents, long maxTotalInputBytes, int maxEvidenceResponseBytes) {
        return new CollectionBudget(totalDuration, startedAt, maxRequests, maxPods, maxEvents, maxTotalInputBytes,
                maxEvidenceResponseBytes);
    }
}
