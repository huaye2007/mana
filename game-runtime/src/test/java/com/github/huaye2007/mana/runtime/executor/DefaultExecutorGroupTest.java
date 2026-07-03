package com.github.huaye2007.mana.runtime.executor;

import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskType;
import com.github.huaye2007.mana.runtime.runnable.IGameTaskRunnable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutorGroupTest {

    private static IGameTaskRunnable task(byte group, long routerKey, Runnable body) {
        GameTaskContext context = new GameTaskContext(GameTaskType.CALLBACK, group, routerKey, (byte) 0, 0L, null);
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
    void sameRouterKeyRunsSeriallyInSubmitOrderOnSameThread() throws Exception {
        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads((byte) 1, "test", 4, 1024);
        try {
            int taskCount = 200;
            List<Integer> order = new CopyOnWriteArrayList<>();
            Set<String> threads = ConcurrentHashMap.newKeySet();
            CountDownLatch done = new CountDownLatch(taskCount);

            for (int i = 0; i < taskCount; i++) {
                int seq = i;
                group.execGameTask(task((byte) 1, 42L, () -> {
                    order.add(seq);
                    threads.add(Thread.currentThread().getName());
                    done.countDown();
                }));
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, threads.size(), "同 routerKey 必须落在同一线程");
            for (int i = 0; i < taskCount; i++) {
                assertEquals(i, order.get(i), "同 routerKey 必须按提交顺序执行");
            }
        } finally {
            group.shutdown(1000);
        }
    }

    @Test
    void differentRouterKeysSpreadAcrossWorkers() throws Exception {
        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads((byte) 2, "test", 4, 1024);
        try {
            Set<String> threads = ConcurrentHashMap.newKeySet();
            CountDownLatch done = new CountDownLatch(16);
            for (long key = 1; key <= 16; key++) {
                group.execGameTask(task((byte) 2, key, () -> {
                    threads.add(Thread.currentThread().getName());
                    done.countDown();
                }));
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(4, threads.size(), "连续 routerKey 应铺满 4 个 worker");
        } finally {
            group.shutdown(1000);
        }
    }

    @Test
    void virtualThreadWorkerKeepsSameRouterKeySerial() throws Exception {
        DefaultExecutorGroup group = DefaultExecutorGroup.virtualThreads((byte) 4, "test-vt", 2, 1024);
        try {
            int taskCount = 100;
            List<Integer> order = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(taskCount);
            CountDownLatch isVirtual = new CountDownLatch(1);

            for (int i = 0; i < taskCount; i++) {
                int seq = i;
                group.execGameTask(task((byte) 4, 7L, () -> {
                    if (Thread.currentThread().isVirtual()) {
                        isVirtual.countDown();
                    }
                    order.add(seq);
                    done.countDown();
                }));
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(isVirtual.await(0, TimeUnit.SECONDS), "worker 应是虚拟线程");
            for (int i = 0; i < taskCount; i++) {
                assertEquals(i, order.get(i), "虚拟线程 worker 同样要保证同 routerKey 串行有序");
            }
        } finally {
            group.shutdown(1000);
        }
    }

    @Test
    void queueFullDropsTaskWithoutThrowing() throws Exception {
        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads((byte) 3, "test", 1, 1);
        try {
            CountDownLatch blockWorker = new CountDownLatch(1);
            CountDownLatch workerStarted = new CountDownLatch(1);
            group.execGameTask(task((byte) 3, 1L, () -> {
                workerStarted.countDown();
                try {
                    blockWorker.await();
                } catch (InterruptedException ignored) {
                }
            }));
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            // 队列容量 1：第二个排队，第三个触发拒绝策略 —— 丢弃但不抛异常
            group.execGameTask(task((byte) 3, 1L, () -> {
            }));
            group.execGameTask(task((byte) 3, 1L, () -> {
            }));
            blockWorker.countDown();
        } finally {
            group.shutdown(1000);
        }
    }
}
