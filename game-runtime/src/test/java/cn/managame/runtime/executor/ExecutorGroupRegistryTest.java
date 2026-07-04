package cn.managame.runtime.executor;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.runnable.IGameTaskRunnable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorGroupRegistryTest {

    private static final byte GROUP_X = 41;
    private static final byte GROUP_Y = 42;

    private static IGameTaskRunnable task(byte group, Runnable body) {
        GameTaskContext context = new GameTaskContext(GameTaskType.CALLBACK, group, 1L, (byte) 0, 0L, null);
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
    void shutdownAllWaitsForQueuedTasksAcrossGroups() throws Exception {
        ExecutorGroupRegistry registry = new ExecutorGroupRegistry();
        registry.register(DefaultExecutorGroup.platformThreads(GROUP_X, "reg-x", 1, 16));
        registry.register(DefaultExecutorGroup.virtualThreads(GROUP_Y, "reg-y", 1, 16));

        AtomicInteger executed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        registry.execute(task(GROUP_X, () -> {
            executed.incrementAndGet();
            done.countDown();
        }));
        registry.execute(task(GROUP_Y, () -> {
            executed.incrementAndGet();
            done.countDown();
        }));

        registry.shutdownAll(5000);
        assertTrue(done.await(1, TimeUnit.SECONDS), "shutdownAll 应等待已入队任务执行完");
        assertEquals(2, executed.get());
    }

    @Test
    void shutdownAllForcesInterruptWhenBudgetExhausted() throws Exception {
        ExecutorGroupRegistry registry = new ExecutorGroupRegistry();
        registry.register(DefaultExecutorGroup.platformThreads(GROUP_X, "reg-slow", 1, 16));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        registry.execute(task(GROUP_X, () -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        }));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        registry.shutdownAll(200);
        assertTrue(System.currentTimeMillis() - begin < 5000, "预算耗尽后应强制中断而不是等任务跑完");
        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "超时后 worker 应被中断");
    }
}
