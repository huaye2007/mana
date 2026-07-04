package cn.managame.runtime.timer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingWheelTest {

    private static final TimingWheel WHEEL = new TimingWheel("test-wheel", 20, 64);

    @AfterAll
    static void tearDown() {
        WHEEL.shutdown();
    }

    @Test
    void firesAfterDelay() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        Timeout timeout = WHEEL.schedule(100, fired::countDown);

        assertTrue(fired.await(2, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 80, "不应明显早于 delay 触发, elapsed=" + elapsed);
        assertTrue(timeout.isExpired());
    }

    @Test
    void cancelBeforeFirePreventsExecution() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Timeout timeout = WHEEL.schedule(150, runs::incrementAndGet);

        assertTrue(timeout.cancel());
        assertTrue(timeout.isCancelled());
        Thread.sleep(400);
        assertEquals(0, runs.get(), "已取消的任务不应执行");
        assertFalse(timeout.cancel(), "重复 cancel 应返回 false");
    }

    @Test
    void delayLongerThanOneRoundStillFires() throws Exception {
        // tick=20ms, wheelSize=64 → 一圈 1280ms；延迟 1500ms 需要 remainingRounds
        CountDownLatch fired = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        WHEEL.schedule(1500, fired::countDown);

        assertTrue(fired.await(5, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 1400, "跨圈延迟不应提前触发, elapsed=" + elapsed);
    }

    @Test
    void pendingCountTracksScheduleFireAndCancel() throws Exception {
        TimingWheel wheel = new TimingWheel("pending-wheel", 20, 64);
        try {
            CountDownLatch fired = new CountDownLatch(1);
            Timeout a = wheel.schedule(200, fired::countDown);
            Timeout b = wheel.schedule(200, () -> {
            });
            assertEquals(2, wheel.pendingCount());

            b.cancel();
            assertTrue(fired.await(2, TimeUnit.SECONDS));
            // 已取消任务在到点扫描时惰性清除，稍等一个 tick 周期
            Thread.sleep(200);
            assertEquals(0, wheel.pendingCount(), "触发和取消清扫后积压应归零");
        } finally {
            wheel.shutdown();
        }
    }

    @Test
    void taskExceptionDoesNotKillWorker() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        WHEEL.schedule(50, () -> {
            throw new IllegalStateException("boom");
        });
        WHEEL.schedule(150, fired::countDown);

        assertTrue(fired.await(2, TimeUnit.SECONDS), "前一个任务抛异常后时间轮应继续工作");
    }
}
