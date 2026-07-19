package cn.managame.runtime.executor;


import cn.managame.runtime.runnable.IGameTaskRunnable;

public interface IExecutorGroup {

    /**
     * 组编号。标准组见 {@link ExecutorGroups}。
     */
    byte group();

    void execGameTask(IGameTaskRunnable gameTaskRunnable);

    /**
     * Attempts to submit a task and reports whether ownership was transferred to the group.
     * Custom implementations that only implement the legacy {@link #execGameTask} API keep
     * source compatibility, but should override this method to report rejection accurately.
     */
    default TaskSubmissionResult tryExecGameTask(IGameTaskRunnable gameTaskRunnable) {
        execGameTask(gameTaskRunnable);
        return TaskSubmissionResult.ACCEPTED;
    }

    /**
     * 优雅停机：不再接收新任务，等待已入队任务执行完；超时后强制中断。
     * 默认空实现，无内部线程的实现可不覆写。
     */
    default void shutdown(long timeoutMs) {
    }
}
