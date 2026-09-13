package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import cn.managame.network.Connection;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.managame.rpc.core.RpcTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RpcCallStopTest {
    final RpcNodeTest fixture = new RpcNodeTest();
    @AfterEach void close() { fixture.close(); }

    @Test void stoppingCallsSettlesPendingButKeepsRepliesAndConnectionsAvailable() throws Exception {
        AtomicReference<Connection> incoming = new AtomicReference<>();
        AtomicInteger incomingId = new AtomicInteger(), completed = new AtomicInteger();
        var a = fixture.node(10, (connection, message) -> {
            incoming.set(connection); incomingId.set(((RpcRequest) message).requestId());
        });
        var b = fixture.node(20, (connection, message) -> {});
        fixture.connect(a, b, 1);
        var outbound = new CompletableFuture<RpcResult>();
        a.call(20, 1, body("pending"), RpcOptions.DEFAULT, result -> {
            completed.incrementAndGet(); outbound.complete(result);
            // Reentrant calls are rejected; no transport or lifecycle lock is held here.
            a.stopCalls();
        });
        var reply = new CompletableFuture<RpcResult>();
        b.call(10, 1, body("incoming"), RpcOptions.DEFAULT, reply::complete);
        await(() -> incoming.get() != null && a.peer(20).pendingCount() == 1);
        a.stopCalls();
        assertEquals(RpcError.UNAVAILABLE, outbound.get(3, TimeUnit.SECONDS).error());
        assertEquals(0, a.peer(20).pendingCount());
        assertTrue(a.isRunning());
        assertTrue(a.peer(20).isReady());
        assertTrue(a.reply(incoming.get(), RpcResponse.error(incomingId.get(), RpcError.NO_HANDLER)));
        assertEquals(RpcError.NO_HANDLER, reply.get(3, TimeUnit.SECONDS).error());
        var rejected = new CompletableFuture<RpcResult>();
        a.call(20, 1, body("new"), RpcOptions.DEFAULT, rejected::complete);
        assertEquals(RpcError.UNAVAILABLE, rejected.get(3, TimeUnit.SECONDS).error());
        assertFalse(a.send(20, 1, body("notification"), RpcOptions.DEFAULT));
        a.stopCalls(); a.close();
        assertEquals(1, completed.get());
    }

    @Test void stopRacingRegistrationLeavesNoPendingOrDuplicateCompletion() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        AtomicInteger completions = new AtomicInteger();
        var results = new ConcurrentLinkedQueue<CompletableFuture<RpcResult>>();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(5)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(workers.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int i = 0; i < 100; i++) {
                    var result = new CompletableFuture<RpcResult>(); results.add(result);
                    a.call(20, 1, body("request"), RpcOptions.DEFAULT, value -> {
                        completions.incrementAndGet(); result.complete(value);
                    });
                }
            }));
            tasks.add(workers.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                a.stopCalls();
            }));
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        for (var result : results) assertEquals(RpcError.UNAVAILABLE, result.get(3, TimeUnit.SECONDS).error());
        assertEquals(400, results.size());
        assertEquals(400, completions.get());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test void localSubmissionFailurePreservesOriginalCauseForTheCaller() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        a.timer.stop();
        var result = new CompletableFuture<RpcResult>();
        a.call(20, 1, body("timer-stopped"), RpcOptions.DEFAULT, result::complete);
        var failure = result.get(3, TimeUnit.SECONDS);
        assertEquals(RpcError.INTERNAL_ERROR, failure.error());
        assertInstanceOf(IllegalStateException.class, failure.cause());
        assertTrue(failure.errorArgs().isEmpty());
        assertTrue(fixture.diagnostics.stream().anyMatch(event -> event.cause() == failure.cause()));
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test void remoteArgumentsAreOwnedAndLocalCauseIsNeverAttachedToAResponse() {
        var result = RpcResult.received(RpcResponse.error(1, new RpcError(1001, "gold"), "101", "100"));
        assertEquals(List.of("101", "100"), result.errorArgs());
        assertThrows(UnsupportedOperationException.class, () -> result.errorArgs().add("mutable"));
        assertNull(result.cause());
        assertThrows(IllegalArgumentException.class, () -> new RpcResult(result.value(), result.error(), new Exception()));
    }
}
