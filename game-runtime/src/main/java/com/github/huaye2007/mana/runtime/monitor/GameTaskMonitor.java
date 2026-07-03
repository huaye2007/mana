package com.github.huaye2007.mana.runtime.monitor;

import com.github.huaye2007.mana.runtime.context.GameTaskContext;

/**
 * 任务执行监控回调。宿主可通过 {@link GameTaskMonitors#setMonitor} 替换默认实现
 * 接入自己的指标系统（如 Prometheus/内部监控）。
 *
 * <p>回调在 worker 线程上同步执行，实现必须轻量非阻塞；
 * 实现抛出的异常由 {@link GameTaskMonitors} 兜底，不会影响任务执行。</p>
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
     * worker 队列满，任务被丢弃（不缓冲不重试，丢弃即终态）。
     */
    void onTaskDropped(GameTaskContext context);
}
