package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcResponse;
import io.netty.buffer.Unpooled;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(10)
class RpcCompletionOrderTest {
    enum Outcome { SUCCESS, BUSINESS_ERROR, TIMEOUT }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void cleanupPrecedesSynchronousCallbackAndFreesCapacityForReentrantCalls(Outcome outcome)
            throws Exception {
        var timer = new ManualTimer();
        var calls = new RpcCalls(timer, null, Duration.ofSeconds(10), () -> true,
                (event, peerId, requestId, connection, failure) -> {});
        var peer = new RpcPeer(20, 1, 1);
        peer.connectionReady(0, null, false);
        var pending = new AtomicReference<RpcFuture>();
        var response = new AtomicReference<RpcResponse>();
        var observed = new TestSignal<Void>();
        var notifications = new AtomicInteger();
        var followups = new AtomicInteger();
        var caller = Thread.currentThread();
        pending.set(calls.begin(peer, calls.deadline(null), result -> {
            try {
                notifications.incrementAndGet();
                assertSame(caller, Thread.currentThread(), "RPC must notify on the completing thread");
                assertTrue(pending.get().isDone());
                assertNull(peer.pending(pending.get().requestId));
                assertEquals(0, peer.pendingCount(), "Pending capacity must be released before notification");
                assertTrue(timer.timeouts.getFirst().isCancelled(), "Timeout must be cancelled before notification");
                assertFalse(Thread.holdsLock(pending.get()), "Callback must run outside the call monitor");
                assertFalse(Thread.holdsLock(peer), "Callback must run outside the peer monitor");
                if (outcome == Outcome.TIMEOUT) {
                    assertEquals(RpcError.TIMEOUT, result.error());
                    assertNull(result.value());
                } else {
                    assertSame(response.get(), result.value(), "The response is borrowed directly during notification");
                }

                // The peer allows only one pending call; this can succeed only after cleanup.
                var next = calls.begin(peer, calls.deadline(null), ignored -> followups.incrementAndGet());
                calls.fail(peer, next, RpcError.UNAVAILABLE);
                assertEquals(1, followups.get());
                observed.complete(null);
            } catch (Throwable failure) {
                observed.completeExceptionally(failure);
            }
        }));
        assertEquals(1, peer.pendingCount());
        assertFalse(timer.timeouts.getFirst().isCancelled());

        var body = Unpooled.buffer().writeInt(42);
        try {
            response.set(outcome == Outcome.BUSINESS_ERROR
                    ? RpcResponse.error(pending.get().requestId, RpcError.fromCode(3001), "denied")
                    : new RpcResponse(pending.get().requestId, 0, RpcMetadata.EMPTY, body));
            if (outcome == Outcome.TIMEOUT) calls.timeoutCall(peer, pending.get());
            else calls.receiveResponse(peer, response.get());

            assertTrue(observed.isDone(), "Callback must finish before completion handling returns");
            observed.get(0, TimeUnit.NANOSECONDS);
            assertEquals(1, body.refCnt(), "RPC completion must not retain or consume the borrowed response body");
            calls.receiveResponse(peer, response.get());
            calls.timeoutCall(peer, pending.get());
            assertEquals(1, notifications.get());
            assertEquals(0, peer.pendingCount());
            assertTrue(timer.timeouts.stream().allMatch(ManualTimeout::isCancelled));
        } finally {
            body.release();
        }
    }

    private static final class ManualTimer implements Timer {
        final List<ManualTimeout> timeouts = new ArrayList<>();

        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            var timeout = new ManualTimeout(this, task);
            timeouts.add(timeout);
            return timeout;
        }

        public Set<Timeout> stop() { return Set.copyOf(timeouts); }
    }

    private static final class ManualTimeout implements Timeout {
        private final Timer timer;
        private final TimerTask task;
        private boolean cancelled;

        ManualTimeout(Timer timer, TimerTask task) { this.timer = timer; this.task = task; }
        public Timer timer() { return timer; }
        public TimerTask task() { return task; }
        public boolean isExpired() { return false; }
        public boolean isCancelled() { return cancelled; }
        public boolean cancel() {
            if (cancelled) return false;
            cancelled = true;
            return true;
        }
    }
}
