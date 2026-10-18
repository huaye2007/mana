package cn.managame.data;

/**
 * Approximate diagnostics. Queued counts are not persistence acknowledgements or byte limits.
 *
 * @param droppedLogs logs refused by LogRepository.insert because their queue was full
 * @param rejectedChanges state changes refused because the dirty set stayed full for pendingChangeWait;
 *                        they were never accepted, so they are not drops and no Handler saw them
 */
public record DataStats(boolean accepting, long pendingChanges, long queuedLogs, long failedBatches,
                        long flushes, long saveNanos, long singleCacheEntries, long groupCacheEntries,
                        long droppedLogs, long rejectedChanges) {}
