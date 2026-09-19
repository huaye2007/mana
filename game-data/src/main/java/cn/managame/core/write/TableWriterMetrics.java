package cn.managame.core.write;

/** Lightweight runtime metrics for one or more table-affine writers. */
public record TableWriterMetrics(
        TableWriterState state,
        int queueSize,
        long pendingCount,
        long successfulBatches,
        long successfulOperations,
        long failedBatches,
        int lastBatchSize,
        long lastFlushNanos,
        long maxFlushNanos,
        long lastFailureEpochMillis) {

    public static TableWriterMetrics empty() {
        return new TableWriterMetrics(TableWriterState.RUNNING, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
