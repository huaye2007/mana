package com.github.huaye2007.mana.runtime.executor;


import com.github.huaye2007.mana.runtime.runnable.IGameTaskRunnable;

public interface IExecutorGroup {

    /**
     * 组编号。标准组见 {@link ExecutorGroups}。
     */
    byte group();

    void execGameTask(IGameTaskRunnable gameTaskRunnable);

    /**
     * 优雅停机：不再接收新任务，等待已入队任务执行完；超时后强制中断。
     * 默认空实现，无内部线程的实现可不覆写。
     */
    default void shutdown(long timeoutMs) {
    }
}
