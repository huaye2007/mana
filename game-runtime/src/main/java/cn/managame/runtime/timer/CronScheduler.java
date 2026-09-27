package cn.managame.runtime.timer;

import cn.managame.runtime.time.GameTime;

/** Manages registered cron methods by declaring class and method name. */
public interface CronScheduler {
    /**
     * Disables a registered active cron, including its subsequent cycles.
     * An invocation already running can finish. Unknown/inactive keys return false.
     */
    boolean cancel(Class<?> type, String methodName);

    /**
     * Replaces the current schedule using GameTime, reactivating a cancelled cron.
     * Unknown keys return false. A closed runtime rejects rescheduling.
     */
    boolean reschedule(Class<?> type, String methodName);

    /** Reschedules all registered crons, including previously cancelled entries. */
    void rescheduleAll();
}
