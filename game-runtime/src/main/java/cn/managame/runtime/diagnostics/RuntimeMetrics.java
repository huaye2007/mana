package cn.managame.runtime.diagnostics;

/**
 * Approximate concurrent snapshot. Timing uses monotonic nanoseconds; durations include
 * parameter resolution and exception observation within the route invocation.
 */
public record RuntimeMetrics(int outstandingTasks, int activeRoutes, int scheduledTimers,
                             long completedTasks, long failedTasks, long rejectedTasks,
                             long totalQueueNanos, long maxQueueNanos,
                             long totalExecutionNanos, long maxExecutionNanos) {}
