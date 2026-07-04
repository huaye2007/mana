package cn.managame.runtime.timer;

import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.runnable.GameTimerTaskRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.ZoneId;

/**
 * Cron 定时任务。调用 {@link #start()} 注册到默认时间轮，每次触发后自动计算下一次
 * 执行时间并重新调度，直到 {@link #cancel()}。触发时按 {@link GameTimerTaskRunnable}
 * 上下文中的 group 派发到对应执行器组，不在计时线程上跑业务。
 *
 * <p>周期/固定频率等其它调度形态由业务在 {@link TimingWheel} 上自行封装。</p>
 */
public class CronTask {

    private final static Logger logger = LoggerFactory.getLogger(CronTask.class);

    private final CronExpression cronExpression;
    private final GameTimerTaskRunnable gameTimerTaskRunnable;
    private volatile boolean cancelled;

    public CronTask(String cron, GameTimerTaskRunnable gameTimerTaskRunnable) {
        this(cron, ZoneId.systemDefault(), gameTimerTaskRunnable);
    }

    /**
     * @param zoneId cron 按哪个时区解释，跨时区部署时显式指定
     */
    public CronTask(String cron, ZoneId zoneId, GameTimerTaskRunnable gameTimerTaskRunnable) {
        this.cronExpression = new CronExpression(cron, zoneId);
        this.gameTimerTaskRunnable = gameTimerTaskRunnable;
    }

    /**
     * 启动调度：算出下一次触发并挂到默认时间轮，到点派发任务后自动重排，直到 {@link #cancel()}。
     */
    public void start() {
        scheduleNext();
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
        TimingWheel.getInstance().schedule(delayMs, () -> {
            if (cancelled) {
                return;
            }
            ExecutorGroupRegistry.getInstance().execute(gameTimerTaskRunnable);
            scheduleNext();
        });
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
