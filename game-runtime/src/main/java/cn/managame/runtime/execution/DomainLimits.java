package cn.managame.runtime.execution;

/**
 * Admission capacity for one execution domain. Counts accepted running and queued tasks.
 * Callbacks can also use reserved slots; the combined capacity must fit in an int.
 */
public record DomainLimits(int maxTasks, int maxTasksPerRoute,
                           int callbackReserve, int callbackReservePerRoute) {
    public DomainLimits {
        if (maxTasks < 1 || maxTasksPerRoute < 1)
            throw new IllegalArgumentException("Task capacities must be positive");
        if (callbackReserve < 0 || callbackReservePerRoute < 0
                || (long) maxTasks + callbackReserve > Integer.MAX_VALUE
                || (long) maxTasksPerRoute + callbackReservePerRoute > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid callback capacity reserve");
    }

    public static DomainLimits defaults() {
        return new DomainLimits(100_000, 4096, 10_000, 64);
    }
}
