package cn.managame.runtime.timer;

import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.runnable.GameTimerTaskRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Cron task backed by the shared timing wheel and a routed game task.
 */
public class CronTask {

    private static final Logger logger = LoggerFactory.getLogger(CronTask.class);

    private final CronExpression cronExpression;
    private final GameTimerTaskRunnable gameTimerTaskRunnable;
    private final Object lifecycleLock = new Object();
    private boolean started;
    private Timeout scheduled;
    private volatile boolean cancelled;

    public CronTask(String cron, GameTimerTaskRunnable gameTimerTaskRunnable) {
        this(cron, ZoneId.systemDefault(), gameTimerTaskRunnable);
    }

    public CronTask(String cron, ZoneId zoneId, GameTimerTaskRunnable gameTimerTaskRunnable) {
        this.cronExpression = new CronExpression(cron, zoneId);
        this.gameTimerTaskRunnable = Objects.requireNonNull(gameTimerTaskRunnable, "gameTimerTaskRunnable");
    }

    /** Starts exactly one scheduling chain. */
    public void start() {
        synchronized (lifecycleLock) {
            if (started) {
                throw new IllegalStateException("cron task already started");
            }
            if (cancelled) {
                throw new IllegalStateException("cron task already cancelled");
            }
            started = true;
        }
        try {
            scheduleNext();
        } catch (RuntimeException e) {
            cancel();
            throw e;
        }
    }

    private void scheduleNext() {
        if (cancelled) {
            return;
        }
        long delayMs = cronExpression.nextDelayMs();
        if (delayMs < 0) {
            logger.warn("Cron task has no next fire time within 2 years, stop scheduling");
            return;
        }
        Timeout next = TimingWheel.getInstance().schedule(delayMs, this::fire);
        synchronized (lifecycleLock) {
            if (cancelled) {
                next.cancel();
            } else {
                scheduled = next;
            }
        }
    }

    private void fire() {
        synchronized (lifecycleLock) {
            scheduled = null;
            if (cancelled) {
                return;
            }
        }
        ExecutorGroupRegistry.getInstance().tryExecute(gameTimerTaskRunnable);
        try {
            scheduleNext();
        } catch (IllegalStateException e) {
            cancel();
            logger.debug("Cron task stopped because the timing wheel is shut down", e);
        }
    }

    public void cancel() {
        Timeout toCancel;
        synchronized (lifecycleLock) {
            cancelled = true;
            toCancel = scheduled;
            scheduled = null;
        }
        if (toCancel != null) {
            toCancel.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
