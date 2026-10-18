package cn.managame.runtime.diagnostic;

/**
 * Concurrent counters are an approximate snapshot; they are not an admission or completion barrier.
 *
 * @param inFlight everything drain waits for: Route work plus captured continuations. Route work alone
 *                 is {@code inFlight - continuations}
 * @param continuations captured continuations reserved and not yet completed or cancelled
 * @param oldestContinuationNanos how long the oldest outstanding continuation has been reserved, or 0
 *                               when none is; a drain that will not finish names itself here
 */
public record RuntimeStats(boolean accepting, long inFlight, long queued, long running,
                           long completed, long rejected, long errors, long executionNanos,
                           long continuations, long oldestContinuationNanos) {}
