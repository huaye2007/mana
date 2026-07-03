package com.github.huaye2007.mana.runtime.executor;

import com.github.huaye2007.mana.runtime.runnable.IGameTaskRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行器组注册表。宿主在启动时注册各 {@link IExecutorGroup} 实现，
 * 运行时按任务上下文中的 group 编号派发任务。
 */
public final class ExecutorGroupRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ExecutorGroupRegistry.class);

    private final static ExecutorGroupRegistry INSTANCE = new ExecutorGroupRegistry();

    private final Map<Byte, IExecutorGroup> groups = new ConcurrentHashMap<>();

    public static ExecutorGroupRegistry getInstance() {
        return INSTANCE;
    }

    public void register(IExecutorGroup executorGroup) {
        IExecutorGroup prev = groups.putIfAbsent(executorGroup.group(), executorGroup);
        if (prev != null) {
            throw new IllegalStateException("Duplicate executor group: " + executorGroup.group());
        }
    }

    public IExecutorGroup get(byte group) {
        return groups.get(group);
    }

    /**
     * 关闭所有已注册执行器组，timeoutMs 是全部组共享的总预算：
     * 顺序关闭时每组用剩余预算等待，预算耗尽后剩余组直接强制中断。
     * 单个组关闭抛异常不影响其余组。
     *
     * <p>宿主停机顺序约定：先 {@code TimingWheel.getInstance().shutdown()} 停止产生新任务，
     * 再调用本方法。</p>
     */
    public void shutdownAll(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (IExecutorGroup executorGroup : groups.values()) {
            try {
                executorGroup.shutdown(Math.max(0, deadline - System.currentTimeMillis()));
            } catch (Throwable e) {
                logger.error("shutdown executor group failed, group={}", executorGroup.group(), e);
            }
        }
    }

    /**
     * 按任务上下文中的 group 派发任务。未注册对应组时记录错误并丢弃任务。
     */
    public void execute(IGameTaskRunnable gameTaskRunnable) {
        byte group = gameTaskRunnable.getGameTaskContext().getGroup();
        IExecutorGroup executorGroup = groups.get(group);
        if (executorGroup == null) {
            logger.error("No executor group registered: {}, task dropped, taskType={}",
                    group, gameTaskRunnable.getGameTaskContext().getTaskType());
            return;
        }
        executorGroup.execGameTask(gameTaskRunnable);
    }
}
