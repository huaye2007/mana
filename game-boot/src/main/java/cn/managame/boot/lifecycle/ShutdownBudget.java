package cn.managame.boot.lifecycle;

import java.time.Duration;

/**
 * One declared budget for the whole shutdown, split into its three phases. Shutdown runs them in sequence —
 * leave rotation, drain admitted work, flush the last changes — so what matters to a deployment is the sum,
 * and the sum is the thing an orchestrator's grace period has to cover. Configuring the phases separately
 * invites a total nobody chose: the phase that only waits is the long one, and the phase that actually
 * writes to the database is the one a SIGKILL reaches last.
 *
 * <p>The total is therefore the configured value and {@link #drain()} is derived from it rather than stored,
 * so the sum cannot drift and no caller can build a budget whose parts disagree with its total. The flush
 * keeps a guaranteed slice rather than whatever is left, because losing the last writes is worse than
 * cutting a drain short.
 *
 * @param total the whole budget; a deployment MUST keep its orchestrator's grace period above this
 * @param lead how long readiness reports failure while work is still admitted, so a load balancer can
 *        notice and stop sending before anything is refused. Zero when nothing probes readiness
 * @param flushReserve the slice reserved for the final persistence pass
 */
public record ShutdownBudget(Duration total, Duration lead, Duration flushReserve) {
    /** Default total, chosen below the 30 seconds a Kubernetes grace period defaults to. */
    public static final long DEFAULT_TOTAL_MILLIS = 25_000;
    /** Default lead: zero, because only the deployment knows its readiness probe interval. */
    public static final long DEFAULT_LEAD_MILLIS = 0;
    /** Default slice reserved for the final flush. */
    public static final long DEFAULT_FLUSH_RESERVE_MILLIS = 10_000;

    /**
     * Rejects a split that leaves the drain nothing. A budget that does not fit is a configuration error at
     * startup rather than a truncated shutdown much later.
     */
    public ShutdownBudget {
        if (total == null || total.isNegative() || total.isZero())
            throw new IllegalArgumentException("total must be positive");
        if (lead == null || lead.isNegative()) throw new IllegalArgumentException("lead must not be negative");
        if (flushReserve == null || flushReserve.isNegative() || flushReserve.isZero())
            throw new IllegalArgumentException("flushReserve must be positive");
        if (total.compareTo(lead.plus(flushReserve)) <= 0)
            throw new IllegalArgumentException("total (" + total + ") must exceed lead (" + lead
                    + ") plus flushReserve (" + flushReserve + ")");
    }

    /** Millisecond form for an integration reading three configuration values. */
    public static ShutdownBudget of(long totalMillis, long leadMillis, long flushReserveMillis) {
        return new ShutdownBudget(Duration.ofMillis(totalMillis), Duration.ofMillis(leadMillis),
                Duration.ofMillis(flushReserveMillis));
    }

    /** {@code total − lead − flushReserve}: the time admitted work has to finish. Always positive. */
    public Duration drain() { return total.minus(lead).minus(flushReserve); }
}
