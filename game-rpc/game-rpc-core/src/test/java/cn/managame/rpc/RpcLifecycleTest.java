package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcLifecycleTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void blockedAdmissionDoesNotBlockTheNodesTimeWheel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var a =
                fixture.builder(10, (c, m) -> {})
                        .peerAdmission(
                                id -> {
                                    if (id == 30) block(entered, release);
                                    return true;
                                })
                        .build();
        fixture.nodes.add(a);
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        var dangling = fixture.network.new Conn(fixture.network.transports.get(10), true);
        dangling.handler.onConnected(dangling);
        var result =
                fixture.call(a, 20, RpcOptions.builder().timeout(Duration.ofMillis(400)).build());
        var c = fixture.node(30, (connection, message) -> {});
        c.start();
        try {
            c.connect(10, "127.0.0.1", a.localAddress().getPort());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
            assertEquals(0, a.peer(20).pendingCount());
        } finally {
            release.countDown();
            dangling.close();
        }
    }

    @Test
    void shutdownReleasesResourcesBeforeNotifyingAndDoesNotHoldStartupLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        var callbacks = new AtomicInteger();
        var result = new AtomicReference<RpcResult>();
        a.call(
                20,
                1,
                body("close"),
                RpcOptions.DEFAULT,
                r -> {
                    callbacks.incrementAndGet();
                    result.set(r);
                    block(entered, release);
                });
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var closing = workers.submit(a::close);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(fixture.network.transports.get(10).closed);
                assertThrows(
                        IllegalStateException.class,
                        () -> a.timer.newTimeout(t -> {}, 1, TimeUnit.SECONDS));
                workers.submit(a::close).get(2, TimeUnit.SECONDS);
                assertFalse(closing.isDone());
                assertEquals(RpcError.UNAVAILABLE, result.get().error());
                release.countDown();
                closing.get(2, TimeUnit.SECONDS);
                assertEquals(1, callbacks.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shutdownSettlesBeforeCleanupAndNotifiesEvenWhenCleanupThrows() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        var transport = fixture.network.transports.get(10);
        transport.dropBusiness = true;
        var result = fixture.call(a, 20, RpcOptions.DEFAULT);
        var peer = a.peer(20);
        var pending = peer.pendingSnapshot().getFirst();
        var failure = new IllegalStateException("Injected cleanup failure");
        transport.closeHook =
                () -> {
                    a.calls.timeoutCall(peer, pending);
                    assertFalse(
                            result.isDone(),
                            "Notify only after resource cleanup leaves the lifecycle lock");
                    assertEquals(0, peer.pendingCount());
                    throw failure;
                };
        assertSame(failure, assertThrows(IllegalStateException.class, a::close));
        assertEquals(RpcError.UNAVAILABLE, result.get(2, TimeUnit.SECONDS).error());
        assertEquals(1L, a.eventCounts().get("call-failure"));
        assertThrows(
                IllegalStateException.class,
                () -> a.timer.newTimeout(t -> {}, 1, TimeUnit.SECONDS));
    }
}
