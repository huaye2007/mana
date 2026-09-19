package cn.managame.rpc.core;

import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcHandshake;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcOptions;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcCallbackThreadTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void timeoutNotifiesDirectlyOnTimeWheelAfterRemovingPending() throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        var remote = fixture.node(20, (c, m) -> {});
        fixture.connect(node, remote, 1);
        var timerThread = new TestSignal<Thread>();
        node.timer.newTimeout(
                t -> timerThread.complete(Thread.currentThread()), 0, TimeUnit.MILLISECONDS);
        var expected = timerThread.get(2, TimeUnit.SECONDS);
        var actual = new TestSignal<Thread>();
        var pendingCount = new AtomicInteger(-1);
        var calls = new AtomicInteger();
        var error = new AtomicReference<RpcError>();
        node.call(
                20,
                1,
                body("timeout"),
                RpcOptions.builder().timeout(Duration.ofMillis(20)).build(),
                result -> {
                    pendingCount.set(node.peer(20).pendingCount());
                    calls.incrementAndGet();
                    error.set(result.error());
                    actual.complete(Thread.currentThread());
                });
        assertSame(expected, actual.get(2, TimeUnit.SECONDS));
        assertEquals(0, pendingCount.get());
        assertEquals(RpcError.TIMEOUT, error.get());
        node.close();
        assertEquals(1, calls.get());
    }

    @Test
    void connectionSuccessUsesHandshakeThreadAndAlreadyReadyUsesCaller() throws Exception {
        var ackThread = new AtomicReference<Thread>();
        var remote =
                fixture.builder(20, (c, m) -> {})
                        .codec(
                                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                                    public ByteBuf encode(RpcMessage message) {
                                        if (message instanceof RpcHandshake h
                                                && h.kind() == RpcHandshake.Kind.ACK)
                                            ackThread.set(Thread.currentThread());
                                        return super.encode(message);
                                    }
                                })
                        .build();
        fixture.nodes.add(remote);
        var node = fixture.node(10, (c, m) -> {});
        remote.start();
        node.start();
        var connectedOn = new TestSignal<Thread>();
        node.connect(20, "127.0.0.1", remote.localAddress().getPort(), callback(connectedOn));
        var completedOn = connectedOn.get(2, TimeUnit.SECONDS);
        assertSame(ackThread.get(), completedOn);
        var alreadyReady = new TestSignal<Thread>();
        var caller = Thread.currentThread();
        node.connect(20, "127.0.0.1", remote.localAddress().getPort(), callback(alreadyReady));
        assertSame(caller, alreadyReady.get(0, TimeUnit.NANOSECONDS));
    }

    private static ConnectCallback callback(TestSignal<Thread> thread) {
        return new ConnectCallback() {
            public void onSuccess(Connection c) {
                thread.complete(Thread.currentThread());
            }

            public void onFailure(Throwable failure) {
                thread.completeExceptionally(failure);
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalAndCloseNotifyOnCallerAfterReleasingLocksAndResources(boolean close)
            throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        node.start();
        var managerField = RpcNode.class.getDeclaredField("connections");
        managerField.setAccessible(true);
        var lockField = RpcConnections.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        Object lock = lockField.get(managerField.get(node));
        var startupField = RpcNode.class.getDeclaredField("startup");
        startupField.setAccessible(true);
        Object startup = startupField.get(node);
        record Notification(
                Thread thread, boolean locked, boolean transportClosed, boolean peerRemoved) {}
        var actual = new TestSignal<Notification>();
        var calls = new AtomicInteger();
        node.connect(
                20,
                "127.0.0.1",
                25000,
                new ConnectCallback() {
                    public void onSuccess(Connection c) {
                        actual.completeExceptionally(new AssertionError("No listener"));
                    }

                    public void onFailure(Throwable failure) {
                        calls.incrementAndGet();
                        actual.complete(
                                new Notification(
                                        Thread.currentThread(),
                                        Thread.holdsLock(lock) || Thread.holdsLock(startup),
                                        fixture.network.transports.get(10).closed,
                                        node.peer(20) == null));
                    }
                });
        var caller = Thread.currentThread();
        if (close) node.close();
        else node.removePeer(20);
        var result = actual.get(0, TimeUnit.NANOSECONDS);
        assertNotNull(result);
        assertSame(caller, result.thread());
        assertFalse(result.locked());
        assertEquals(close, result.transportClosed());
        assertTrue(result.peerRemoved());
        node.close();
        assertEquals(1, calls.get());
    }
}
