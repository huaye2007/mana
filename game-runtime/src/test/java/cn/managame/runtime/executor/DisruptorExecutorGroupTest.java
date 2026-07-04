package cn.managame.runtime.executor;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.runnable.IGameTaskRunnable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisruptorExecutorGroupTest {

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
        DisruptorExecutorGroup group = DisruptorExecutorGroup.blockingWait((byte) 1, "disruptor-test", 4, 1024);
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
        DisruptorExecutorGroup group = DisruptorExecutorGroup.blockingWait((byte) 2, "disruptor-test", 4, 1024);
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
    void ringFullDropsTaskWithoutThrowing() throws Exception {
        DisruptorExecutorGroup group = DisruptorExecutorGroup.blockingWait((byte) 3, "disruptor-test", 1, 2);
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

            // 环容量 2：worker 阻塞时第一个槽位未释放，第二个任务占满环，第三个 tryPublish 失败 —— 丢弃但不抛异常
            group.execGameTask(task((byte) 3, 1L, () -> {
            }));
            group.execGameTask(task((byte) 3, 1L, () -> {
            }));
            assertEquals(1, group.droppedCount(), "环满的任务应被丢弃计数");
            blockWorker.countDown();
        } finally {
            group.shutdown(1000);
        }
    }

    @Test
    void shutdownDrainsQueuedTasksAndRejectsNewOnes() throws Exception {
        DisruptorExecutorGroup group = DisruptorExecutorGroup.blockingWait((byte) 4, "disruptor-test", 2, 1024);
        int taskCount = 100;
        AtomicInteger executed = new AtomicInteger();
        for (int i = 1; i <= taskCount; i++) {
            group.execGameTask(task((byte) 4, i, executed::incrementAndGet));
        }
        group.shutdown(5000);
        assertEquals(taskCount, executed.get(), "优雅停机必须先跑完已入环的任务");

        group.execGameTask(task((byte) 4, 1L, executed::incrementAndGet));
        assertEquals(taskCount, executed.get(), "停机后新任务应被丢弃");
        assertEquals(1, group.droppedCount());
    }

    @Test
    void bufferSizeMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> DisruptorExecutorGroup.blockingWait((byte) 5, "disruptor-test", 1, 1000));
    }
}
