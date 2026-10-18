package cn.managame.runtime.timer;

import java.time.Duration;

public interface RuntimeTimer {
    TimerRef schedule(int routeDomain, long routeKey, Duration delay, Runnable task);

    /**
     * Cancels every timer this Runtime still holds for one Route, so a Route whose business is ending —
     * a player logging out, a room closing — does not have to be tracked timer by timer.
     *
     * <p>Each cancellation follows {@link TimerRef#cancel()}: a timer already claimed by its trigger is
     * not retracted. The returned count is how many this call moved from pending to cancelled. Timers
     * scheduled concurrently with this call may miss it, and Cron keeps its own schedules — cancel those
     * through {@link CronScheduler}.
     */
    int cancelAll(int routeDomain, long routeKey);
}
