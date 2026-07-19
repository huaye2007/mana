package cn.managame.runtime.monitor;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.executor.TaskSubmissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局任务监控持有者，模式同 {@code GameTaskExceptionHandlers}：
 * 默认实现只做慢任务告警和丢弃日志，宿主启动期可整体替换。
 */
public final class GameTaskMonitors {

    private final static Logger logger = LoggerFactory.getLogger(GameTaskMonitors.class);

    private static volatile long slowTaskThresholdMs = 1000;

    private final static GameTaskMonitor DEFAULT = new GameTaskMonitor() {
        @Override
        public void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
            if (execMs >= slowTaskThresholdMs) {
                logger.warn("slow task, taskType={}, group={}, routerKey={}, queueDelayMs={}, execMs={}",
                        context.getTaskType(), context.getGroup(), context.getRouterKey(), queueDelayMs, execMs);
            }
        }

        @Override
        public void onTaskDropped(GameTaskContext context) {
            logger.error("worker queue full, task dropped, taskType={}, group={}, routerKey={}",
                    context.getTaskType(), context.getGroup(), context.getRouterKey());
        }

        @Override
        public void onTaskRejected(GameTaskContext context, TaskSubmissionResult result) {
            logger.error("task submission rejected, reason={}, taskType={}, group={}, routerKey={}",
                    result, context.getTaskType(), context.getGroup(), context.getRouterKey());
        }
    };

    private static volatile GameTaskMonitor monitor = DEFAULT;

    private GameTaskMonitors() {
    }

    public static void setMonitor(GameTaskMonitor gameTaskMonitor) {
        if (gameTaskMonitor == null) {
            throw new IllegalArgumentException("monitor must not be null");
        }
        monitor = gameTaskMonitor;
    }

    public static void resetToDefault() {
        monitor = DEFAULT;
    }

    /**
     * 默认监控的慢任务阈值（毫秒）。SCENE 这类延迟敏感组建议宿主自定义监控按组分阈值。
     */
    public static void setSlowTaskThresholdMs(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("thresholdMs must be positive: " + thresholdMs);
        }
        slowTaskThresholdMs = thresholdMs;
    }

    /**
     * 监控回调兜底：监控自身异常只打日志，绝不影响任务执行路径。
     */
    public static void taskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
        try {
            monitor.onTaskComplete(context, queueDelayMs, execMs);
        } catch (Exception e) {
            logger.error("task monitor onTaskComplete failed", e);
        }
    }

    public static void taskDropped(GameTaskContext context) {
        taskRejected(context, TaskSubmissionResult.REJECTED_OVERLOADED);
    }

    public static void taskRejected(GameTaskContext context, TaskSubmissionResult result) {
        try {
            monitor.onTaskRejected(context, result);
        } catch (Exception e) {
            logger.error("task monitor onTaskRejected failed", e);
        }
    }
}
