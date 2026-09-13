package cn.managame.runtime.execution;


/**
 * Compatibility configuration for {@link GameRuntime.Builder#limits(RuntimeLimits)}.
 * That bridge applies every field to default-domain capacity, route shards, or timer options.
 *
 * @deprecated Use {@link DomainLimits}, {@link GameRuntime.Builder#routeShards(int)}
 * and {@link TimerOptions}. Explicit execution domains configure their own admission limits.
 */
@Deprecated(forRemoval = true)
public record RuntimeLimits(int maxTasks, int maxTasksPerRoute,
                            int callbackReserve, int callbackReservePerRoute,
                            int maxTimers, int routeShards, int maxTimersPerPoll) {
    public RuntimeLimits {
        new DomainLimits(maxTasks, maxTasksPerRoute, callbackReserve, callbackReservePerRoute);
        new TimerOptions(maxTimers, maxTimersPerPoll);
        if (routeShards < 1 || routeShards > 4096)
            throw new IllegalArgumentException("Route shards must be 1..4096");
    }

    public DomainLimits domainLimits() {
        return new DomainLimits(maxTasks, maxTasksPerRoute, callbackReserve, callbackReservePerRoute);
    }

    public TimerOptions timerOptions() {
        return new TimerOptions(maxTimers, maxTimersPerPoll);
    }

    public static RuntimeLimits defaults() {
        DomainLimits domain = DomainLimits.defaults();
        TimerOptions timers = TimerOptions.defaults();
        return new RuntimeLimits(domain.maxTasks(), domain.maxTasksPerRoute(),
                domain.callbackReserve(), domain.callbackReservePerRoute(),
                timers.maxTimers(), 64, timers.maxTimersPerPoll());
    }
}
