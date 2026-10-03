package cn.managame.data;

/** Approximate diagnostics. Queued counts are not persistence acknowledgements or byte limits. */
public record DataStats(boolean accepting, long pendingChanges, long queuedLogs, long failedBatches,
                        long flushes, long saveNanos, long singleCacheEntries, long groupCacheEntries) {}
