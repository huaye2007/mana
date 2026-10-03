package cn.managame.runtime.diagnostic;

/** Concurrent counters are an approximate snapshot; they are not an admission or completion barrier. */
public record RuntimeStats(boolean accepting, long inFlight, long queued, long running,
                           long completed, long rejected, long errors, long executionNanos) {}
