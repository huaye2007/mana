package com.github.huaye2007.mana.runtime.executor;

import com.github.huaye2007.mana.runtime.monitor.GameTaskMonitors;
import com.github.huaye2007.mana.runtime.runnable.IGameTaskRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认执行器组实现：组内 N 个单线程 worker，任务按 routerKey 哈希落到
 * 固定 worker，保证同一 routerKey（如同一玩家）的任务串行执行。
 *
 * <p>worker 线程类型可选：登陆/玩家/公共这类可能阻塞的业务组用虚拟线程，
 * 场景这类不能卡顿的组用平台线程（见 {@link ExecutorGroups}）。</p>
 *
 * <p>worker 队列有界；队列满时任务直接丢弃并记错误日志，不缓冲不重试。</p>
 */
public class DefaultExecutorGroup implements IExecutorGroup {

    private final static Logger logger = LoggerFactory.getLogger(DefaultExecutorGroup.class);

    private final byte group;
    private final ThreadPoolExecutor[] workers;
    private final AtomicLong droppedCount = new AtomicLong();

    /** 任务异常已在 runnable 内兜底，这里只兜框架级意外（如 OOM 后的残余异常） */
    private static final Thread.UncaughtExceptionHandler UNCAUGHT = (t, e) ->
            logger.error("uncaught exception in worker thread {}", t.getName(), e);

    public static DefaultExecutorGroup virtualThreads(byte group, String name, int threads, int queueCapacity) {
        return new DefaultExecutorGroup(group, name, threads, queueCapacity, true);
    }

    public static DefaultExecutorGroup platformThreads(byte group, String name, int threads, int queueCapacity) {
        return new DefaultExecutorGroup(group, name, threads, queueCapacity, false);
    }

    public DefaultExecutorGroup(byte group, String name, int threads, int queueCapacity, boolean virtualThreads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive: " + threads);
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive: " + queueCapacity);
        }
        this.group = group;
        this.workers = new ThreadPoolExecutor[threads];
        for (int i = 0; i < threads; i++) {
            String threadName = name + "-group" + group + "-worker-" + i;
            ThreadFactory threadFactory = virtualThreads
                    ? r -> Thread.ofVirtual().name(threadName).uncaughtExceptionHandler(UNCAUGHT).unstarted(r)
                    : r -> {
                        Thread t = new Thread(r, threadName);
                        t.setDaemon(true);
                        t.setUncaughtExceptionHandler(UNCAUGHT);
                        return t;
                    };
            workers[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    threadFactory,
                    (r, executor) -> {
                        droppedCount.incrementAndGet();
                        GameTaskMonitors.taskDropped(((MonitoredTask) r).task.getGameTaskContext());
                    });
        }
    }

    @Override
    public byte group() {
        return group;
    }

    @Override
    public void execGameTask(IGameTaskRunnable gameTaskRunnable) {
        long routerKey = gameTaskRunnable.getGameTaskContext().getRouterKey();
        int index = (int) Math.floorMod(routerKey, workers.length);
        workers[index].execute(new MonitoredTask(gameTaskRunnable));
    }

    /**
     * 累计被丢弃（队列满）的任务数。
     */
    public long droppedCount() {
        return droppedCount.get();
    }

    /**
     * 当前所有 worker 队列中等待执行的任务总数（瞬时值，仅用于监控采样）。
     */
    public int queuedTasks() {
        int sum = 0;
        for (ThreadPoolExecutor worker : workers) {
            sum += worker.getQueue().size();
        }
        return sum;
    }

    /**
     * 包装任务记录排队/执行耗时，完成后回调 {@link GameTaskMonitors}。
     */
    private static final class MonitoredTask implements Runnable {

        final IGameTaskRunnable task;
        final long enqueueNanos = System.nanoTime();

        MonitoredTask(IGameTaskRunnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            long startNanos = System.nanoTime();
            try {
                task.run();
            } finally {
                GameTaskMonitors.taskComplete(task.getGameTaskContext(),
                        (startNanos - enqueueNanos) / 1_000_000,
                        (System.nanoTime() - startNanos) / 1_000_000);
            }
        }
    }

    /**
     * 优雅停机：不再接收新任务，等待已入队任务执行完；超时后强制中断。
     */
    @Override
    public void shutdown(long timeoutMs) {
        for (ThreadPoolExecutor worker : workers) {
            worker.shutdown();
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (ThreadPoolExecutor worker : workers) {
            try {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0 || !worker.awaitTermination(remain, TimeUnit.MILLISECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
    }
}
