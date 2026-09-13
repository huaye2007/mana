package cn.managame.runtime.execution;

/** Runtime-wide business timer settings. Timer capacity includes cron registrations. */
public record TimerOptions(int maxTimers, int maxTimersPerPoll) {
    public TimerOptions {
        if (maxTimers < 1 || maxTimersPerPoll < 1)
            throw new IllegalArgumentException("Timer capacity and poll batch size must be positive");
    }

    public static TimerOptions defaults() {
        return new TimerOptions(100_000, 1024);
    }
}
