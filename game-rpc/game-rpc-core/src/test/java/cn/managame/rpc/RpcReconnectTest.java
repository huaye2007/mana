package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.util.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class RpcReconnectTest {
    @Test
    void retriesGrowToCapWithJitterAndResetIndependentlyPerSlot() {
        var plan = new RpcConnections.Outbound("localhost", 1, 2);
        var values = new HashSet<Long>();
        long[] ceilings = {100, 200, 400, 500, 500};
        for (long ceiling : ceilings) {
            long delay = plan.retryDelay(0, 100, 500);
            assertTrue(delay >= ceiling / 2 && delay <= ceiling);
            assertEquals(ceiling, plan.retryCeilings[0]);
        }
        for (int i = 0; i < 100; i++) values.add(plan.retryDelay(0, 100, 500));
        assertTrue(values.size() > 1, "Retries must not all use the same fixed delay");
        assertEquals(0, plan.retryCeilings[1]);
        plan.retryDelay(1, 100, 500);
        plan.connected(0);
        assertEquals(0, plan.retryCeilings[0]);
        assertEquals(100, plan.retryCeilings[1]);
        long reset = plan.retryDelay(0, 100, 500);
        assertTrue(reset >= 50 && reset <= 100);
    }

    @Test
    void jitterRemainsPositiveAndDoublingDoesNotOverflow() {
        var plan = new RpcConnections.Outbound("localhost", 1, 1);
        assertEquals(1, plan.retryDelay(0, 1, 1));
        plan.connected(0);
        for (int i = 0; i < 10; i++) {
            long value = plan.retryDelay(0, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE);
            assertTrue(value > 0);
            assertTrue(value <= plan.retryCeilings[0]);
        }
        assertEquals(Long.MAX_VALUE, plan.retryCeilings[0]);
    }

    @Test
    void configurationRejectsInvalidDurationsBeforeCreatingTransport() {
        var fixture = new RpcNodeTest();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        fixture.builder(10, (c, m) -> {})
                                .reconnectDelay(Duration.ofSeconds(2))
                                .maxReconnectDelay(Duration.ofSeconds(1))
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.builder(10, (c, m) -> {}).maxReconnectDelay(Duration.ZERO).build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        fixture.builder(10, (c, m) -> {})
                                .readIdleTimeout(Duration.ofMillis(-1))
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        fixture.builder(10, (c, m) -> {})
                                .readIdleTimeout(Duration.ofSeconds(Long.MAX_VALUE))
                                .build());
        assertTrue(fixture.network.transports.isEmpty());
    }

    @Test
    void realConnectFailuresBackOffSuccessResetsAndRemovalCancelsRetry() throws Exception {
        var fixture = new RpcNodeTest();
        var timer = new ManualTimer();
        try {
            var a =
                    fixture.builder(10, (c, m) -> {})
                            .timer(timer)
                            .reconnectDelay(Duration.ofMillis(10))
                            .maxReconnectDelay(Duration.ofMillis(40))
                            .build();
            fixture.nodes.add(a);
            a.start();
            a.connect(20, "127.0.0.1", 65001);
            var first = timer.next();
            assertEquals(0, first.nanos);
            first.fire();
            for (int ceiling : new int[] {10, 20, 40, 40}) {
                var retry = timer.next();
                assertDelay(retry, ceiling);
                a.connect(20, "127.0.0.1", 65001); // Cannot bypass an installed backoff.
                assertFalse(retry.isCancelled());
                retry.fire();
            }
            var last = timer.next();
            assertDelay(last, 40);
            var b = fixture.builder(20, (c, m) -> {}).listen("127.0.0.1", 65001).build();
            fixture.nodes.add(b);
            b.start();
            last.fire();
            await(() -> a.peer(20).isReady());
            a.peer(20).connection(0).close();
            var reset = timer.next();
            assertDelay(reset, 10);
            a.removePeer(20);
            assertTrue(reset.isCancelled());
            reset.fire(); // Even a stale task cannot re-create the removed peer.
            assertNull(a.peer(20));
        } finally {
            fixture.close();
        }
    }

    private static void assertDelay(Task task, int millis) {
        long ceiling = TimeUnit.MILLISECONDS.toNanos(millis);
        assertTrue(
                task.nanos >= ceiling / 2 && task.nanos <= ceiling,
                "Unexpected retry delay: " + task.nanos);
    }

    static class ManualTimer implements io.netty.util.Timer {
        final BlockingQueue<Task> tasks = new LinkedBlockingQueue<>();

        public Timeout newTimeout(io.netty.util.TimerTask action, long delay, TimeUnit unit) {
            var task = new Task(this, action, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        public Set<Timeout> stop() {
            return Set.of();
        }

        Task next() throws Exception {
            for (; ; ) {
                Task task = tasks.poll(5, TimeUnit.SECONDS);
                assertNotNull(task, "Missing scheduled task");
                if (!task.isCancelled()) return task;
            }
        }
    }

    static class Task implements Timeout {
        final ManualTimer timer;
        final io.netty.util.TimerTask action;
        final long nanos;
        volatile boolean cancelled, expired;

        Task(ManualTimer timer, io.netty.util.TimerTask action, long nanos) {
            this.timer = timer;
            this.action = action;
            this.nanos = nanos;
        }

        void fire() throws Exception {
            expired = true;
            action.run(this);
        }

        public io.netty.util.Timer timer() {
            return timer;
        }

        public io.netty.util.TimerTask task() {
            return action;
        }

        public boolean isExpired() {
            return expired;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean cancel() {
            cancelled = true;
            return !expired;
        }
    }
}
