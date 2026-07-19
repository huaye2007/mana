package cn.managame.runtime.monitor;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.executor.TaskSubmissionResult;

/**
 * 任务执行监控回调。宿主可通过 {@link GameTaskMonitors#setMonitor} 替换默认实现
 * 接入自己的指标系统（如 Prometheus/内部监控）。
 *
 * <p>回调在 worker 线程上同步执行，实现必须轻量非阻塞；
 * 实现抛出的 {@link Exception} 由 {@link GameTaskMonitors} 兜底，不会影响任务执行。</p>
 */
public interface GameTaskMonitor {

    /**
     * 任务执行完成（无论业务成功失败都会回调）。
     *
     * @param queueDelayMs 从入队到开始执行的排队耗时
     * @param execMs       任务执行耗时
     */
    void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs);

    /**
     * 兼容回调：任务提交被拒绝（不缓冲不重试，拒绝即终态）。
     */
    void onTaskDropped(GameTaskContext context);

    /**
     * Called when submission is rejected. The default preserves the legacy dropped callback.
     */
    default void onTaskRejected(GameTaskContext context, TaskSubmissionResult result) {
        onTaskDropped(context);
    }
}
