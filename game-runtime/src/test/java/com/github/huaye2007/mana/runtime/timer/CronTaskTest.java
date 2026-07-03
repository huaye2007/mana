package com.github.huaye2007.mana.runtime.timer;

import com.github.huaye2007.mana.runtime.executor.DefaultExecutorGroup;
import com.github.huaye2007.mana.runtime.executor.ExecutorGroupRegistry;
import com.github.huaye2007.mana.runtime.runnable.GameTimerTaskRunnable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronTaskTest {

    private static final byte GROUP = 41;

    private static final DefaultExecutorGroup EXECUTOR =
            DefaultExecutorGroup.platformThreads(GROUP, "cron-test", 1, 256);

    static {
        ExecutorGroupRegistry.getInstance().register(EXECUTOR);
    }

    @AfterAll
    static void tearDown() {
        EXECUTOR.shutdown(1000);
    }

    private static GameTimerTaskRunnable timerTask(Runnable body) {
        return new GameTimerTaskRunnable(GROUP, 1L, (byte) 0, 0L, null, body);
    }

    @Test
    void delayedTaskDispatchesToExecutorGroupThread() throws Exception {
        // 业务封装：时间轮只算时间点，到点把任务派发到执行器组（不在计时线程上跑业务）
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        GameTimerTaskRunnable task = timerTask(() -> {
            threadName.set(Thread.currentThread().getName());
            fired.countDown();
        });

        TimingWheel.getInstance().schedule(100,
                () -> ExecutorGroupRegistry.getInstance().execute(task));

        assertTrue(fired.await(3, TimeUnit.SECONDS));
        assertTrue(threadName.get().startsWith("cron-test"), "到点任务应在执行器组线程上执行");
    }

    @Test
    void cronTaskReschedulesUntilCancelled() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch twoFires = new CountDownLatch(2);

        CronTask cronTask = new CronTask("*/1 * * * * *", timerTask(() -> {
            runs.incrementAndGet();
            twoFires.countDown();
        }));
        cronTask.start();

        assertTrue(twoFires.await(5, TimeUnit.SECONDS), "cron 任务每秒触发，应自动重排");
        cronTask.cancel();
        Thread.sleep(300);
        int after = runs.get();
        Thread.sleep(1500);
        assertEquals(after, runs.get(), "取消后 cron 不应再触发");
    }
}
