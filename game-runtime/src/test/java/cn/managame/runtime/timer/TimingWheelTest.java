package cn.managame.runtime.timer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    @Test
    void deadlineNearTickBoundaryNeverFiresEarly() throws Exception {
        TimingWheel wheel = new TimingWheel("deadline-wheel", 50, 16);
        try {
            CountDownLatch boundary = new CountDownLatch(1);
            wheel.schedule(0, boundary::countDown);
            assertTrue(boundary.await(1, TimeUnit.SECONDS));
            Thread.sleep(35);

            CountDownLatch fired = new CountDownLatch(1);
            long start = System.nanoTime();
            wheel.schedule(50, fired::countDown);
            assertTrue(fired.await(1, TimeUnit.SECONDS));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs >= 45, "timer fired before deadline, elapsed=" + elapsedMs);
        } finally {
            wheel.shutdown();
        }
    }

    @Test
    void cancelAndShutdownMakeOutstandingHandlesTerminal() {
        TimingWheel wheel = new TimingWheel("shutdown-wheel", 20, 16);
        Timeout cancelled = wheel.schedule(60_000, () -> { });
        assertEquals(1, wheel.pendingCount());
        assertTrue(cancelled.cancel());
        assertEquals(0, wheel.pendingCount());

        Timeout shutdown = wheel.schedule(60_000, () -> { });
        assertTrue(wheel.shutdown(1_000));
        assertTrue(shutdown.isCancelled());
        assertEquals(0, wheel.pendingCount());
        assertThrows(IllegalStateException.class, () -> wheel.schedule(1, () -> { }));
    }

    @Test
    void sharedClockCanAdvanceScheduledTimeoutWithoutChangingSystemTime() throws Exception {
        Instant base = Instant.ofEpochMilli(System.currentTimeMillis());
        GameTime.setClock(Clock.fixed(base, ZoneOffset.UTC));
        TimingWheel wheel = new TimingWheel("adjustable-clock-wheel", 20, 16);
        try {
            CountDownLatch fired = new CountDownLatch(1);
            wheel.schedule(200, fired::countDown);

            assertFalse(fired.await(100, TimeUnit.MILLISECONDS));
            GameTime.setClock(Clock.fixed(base.plusMillis(200), ZoneOffset.UTC));
            assertTrue(fired.await(1, TimeUnit.SECONDS));
        } finally {
            wheel.shutdown();
            GameTime.resetClock();
        }
    }
}
