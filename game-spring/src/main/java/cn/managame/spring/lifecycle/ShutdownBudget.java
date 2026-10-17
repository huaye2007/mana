package cn.managame.spring.lifecycle;

import java.time.Duration;

/**
 * One declared budget for the whole shutdown, split into its three phases. Shutdown runs them in sequence —
 * leave rotation, drain admitted work, flush the last changes — so what matters to a deployment is the sum,
 * and the sum is the thing an orchestrator's grace period has to cover. Configuring the phases separately
 * invites a total nobody chose: the phase that only waits is the long one, and the phase that actually
 * writes to the database is the one a SIGKILL reaches last.
 *
 * <p>The total is therefore the configured value and {@link #drain()} is derived from it, so the sum cannot
 * drift. The flush keeps a guaranteed slice rather than whatever is left, because losing the last writes is
 * worse than cutting a drain short.
 *
 * @param total the whole budget; a deployment MUST keep its orchestrator's grace period above this
 * @param lead how long readiness reports failure while work is still admitted, so a load balancer can
 *        notice and stop sending before anything is refused. Zero when nothing probes readiness
 * @param flushReserve the slice reserved for the final persistence pass
 * @param drain {@code total − lead − flushReserve}, the time admitted work has to finish
 */
public record ShutdownBudget(Duration total, Duration lead, Duration flushReserve, Duration drain) {
    /** Default total, chosen below the 30 seconds a Kubernetes grace period defaults to. */
    public static final long DEFAULT_TOTAL_MILLIS = 25_000;
    /** Default lead: zero, because only the deployment knows its readiness probe interval. */
    public static final long DEFAULT_LEAD_MILLIS = 0;
    /** Default slice reserved for the final flush. */
    public static final long DEFAULT_FLUSH_RESERVE_MILLIS = 10_000;

    /**
     * Derives the drain from the other three and rejects a split that leaves it nothing. A budget that does
     * not fit is a configuration error at startup rather than a truncated shutdown much later.
     */
    public static ShutdownBudget of(long totalMillis, long leadMillis, long flushReserveMillis) {
        if (totalMillis <= 0) throw new IllegalArgumentException("game.shutdown.budget-millis must be positive");
        if (leadMillis < 0) throw new IllegalArgumentException("game.shutdown.lead-millis must not be negative");
        if (flushReserveMillis <= 0) throw new IllegalArgumentException("game.shutdown.flush-reserve-millis must be positive");
        long drain = totalMillis - leadMillis - flushReserveMillis;
        if (drain <= 0) throw new IllegalArgumentException("game.shutdown.budget-millis (" + totalMillis
                + ") must exceed lead (" + leadMillis + ") plus flush reserve (" + flushReserveMillis + ")");
        return new ShutdownBudget(Duration.ofMillis(totalMillis), Duration.ofMillis(leadMillis),
                Duration.ofMillis(flushReserveMillis), Duration.ofMillis(drain));
    }
}
