package com.github.huaye2007.mana.runtime.monitor;

import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskType;
import com.github.huaye2007.mana.runtime.executor.DefaultExecutorGroup;
import com.github.huaye2007.mana.runtime.runnable.IGameTaskRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTaskMonitorsTest {

    private static final byte GROUP = 51;

    @AfterEach
    void reset() {
        GameTaskMonitors.resetToDefault();
    }

    private static IGameTaskRunnable task(long routerKey, Runnable body) {
        GameTaskContext context = new GameTaskContext(GameTaskType.CALLBACK, GROUP, routerKey, (byte) 0, 0L, null);
        return new IGameTaskRunnable() {
            @Override
            public void run() {
                body.run();
            }

            @Override
            public GameTaskContext getGameTaskContext() {
                return context;
            }
        };
    }

    @Test
    void monitorReceivesQueueDelayAndExecTime() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicLong seenQueueDelay = new AtomicLong(-1);
        AtomicLong seenExec = new AtomicLong(-1);
        GameTaskMonitors.setMonitor(new GameTaskMonitor() {
            @Override
            public void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
                seenQueueDelay.set(queueDelayMs);
                seenExec.set(execMs);
                completed.countDown();
            }

            @Override
            public void onTaskDropped(GameTaskContext context) {
            }
        });

        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads(GROUP, "mon", 1, 16);
        try {
            group.execGameTask(task(1L, () -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(seenQueueDelay.get() >= 0, "排队耗时应非负");
            assertTrue(seenExec.get() >= 40, "执行耗时应覆盖业务 sleep, execMs=" + seenExec.get());
        } finally {
            group.shutdown(1000);
        }
    }

    @Test
    void queueFullDropsTaskAndNotifiesMonitor() throws Exception {
        CountDownLatch dropped = new CountDownLatch(1);
        AtomicReference<GameTaskContext> droppedCtx = new AtomicReference<>();
        GameTaskMonitors.setMonitor(new GameTaskMonitor() {
            @Override
            public void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
            }

            @Override
            public void onTaskDropped(GameTaskContext context) {
                droppedCtx.set(context);
                dropped.countDown();
            }
        });

        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads(GROUP, "drop", 1, 1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            // 第 1 个占住 worker，第 2 个占满队列，第 3 个必然被丢弃
            group.execGameTask(task(1L, () -> {
                started.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException ignored) {
                }
            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            group.execGameTask(task(1L, () -> {
            }));
            assertEquals(1, group.queuedTasks(), "队列里应有 1 个等待任务");
            group.execGameTask(task(1L, () -> {
            }));

            assertTrue(dropped.await(1, TimeUnit.SECONDS), "队列满应回调 onTaskDropped");
            assertNotNull(droppedCtx.get());
            assertEquals(GROUP, droppedCtx.get().getGroup());
            assertEquals(1, group.droppedCount());
        } finally {
            blocker.countDown();
            group.shutdown(1000);
        }
    }

    @Test
    void monitorExceptionNeverBreaksTaskExecution() throws Exception {
        GameTaskMonitors.setMonitor(new GameTaskMonitor() {
            @Override
            public void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
                throw new IllegalStateException("monitor boom");
            }

            @Override
            public void onTaskDropped(GameTaskContext context) {
            }
        });

        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads(GROUP, "mon-boom", 1, 16);
        CountDownLatch second = new CountDownLatch(1);
        try {
            group.execGameTask(task(1L, () -> {
            }));
            group.execGameTask(task(1L, second::countDown));
            assertTrue(second.await(5, TimeUnit.SECONDS), "监控自身异常不应影响后续任务执行");
        } finally {
            group.shutdown(1000);
        }
    }
}
