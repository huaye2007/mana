package cn.managame.rpc.core;

import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.transport.RpcNetworkProvider;
import cn.managame.rpc.transport.RpcTransport;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.Connection;

import io.netty.buffer.ByteBuf;
import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcCloseReplyTest {
    final RpcNodeTest f = new RpcNodeTest();

    @AfterEach
    void close() {
        f.close();
    }

    Object lifecycleLock(RpcNode node) throws Exception {
        var field = RpcNode.class.getDeclaredField("startup");
        field.setAccessible(true);
        return field.get(node);
    }

    @Test
    void ownedTimerRejectsLifecycleBeforeChangingState() throws Exception {
        var a = f.node(10, (c, m) -> {});
        var b = f.node(20, (c, m) -> {});
        f.connect(a, b, 1);
        var finished = new CompletableFuture<Thread>();
        a.call(
                20,
                1,
                body("timeout"),
                RpcOptions.builder().timeout(Duration.ofMillis(50)).build(),
                r -> {
                    try {
                        assertThrows(IllegalStateException.class, a::close);
                        assertThrows(IllegalStateException.class, a::start);
                        assertTrue(a.isRunning());
                        assertFalse(a.isClosed());
                        assertTrue(a.peer(20).isReady());
                        finished.complete(Thread.currentThread());
                    } catch (Throwable e) {
                        finished.completeExceptionally(e);
                    }
                });
        var worker = finished.get(3, TimeUnit.SECONDS);
        a.close();
        assertFalse(worker.isAlive());
        assertThrows(
                IllegalStateException.class,
                () -> a.timer.newTimeout(t -> {}, 1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentExternalCloseDoesNotDeadlockWithTimeoutCallback() throws Exception {
        var a = f.node(10, (c, m) -> {});
        var b = f.node(20, (c, m) -> {});
        f.connect(a, b, 1);
        Object lock = lifecycleLock(a);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callback = new CompletableFuture<Void>();
        var closed = new CompletableFuture<Void>();
        a.call(
                20,
                1,
                body("timeout"),
                RpcOptions.builder().timeout(Duration.ofMillis(50)).build(),
                r -> {
                    entered.countDown();
                    try {
                        try {
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException stoppingTimer) {
                            // Timer.stop interrupts its worker even after the latch was released.
                            assertEquals(0, release.getCount());
                        }
                        assertThrows(IllegalStateException.class, a::close);
                        assertThrows(IllegalStateException.class, a::start);
                        callback.complete(null);
                    } catch (Throwable e) {
                        callback.completeExceptionally(e);
                    }
                });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var held = new AtomicBoolean();
        f.network.transports.get(10).closeHook =
                () -> {
                    held.set(Thread.holdsLock(lock));
                    release.countDown();
                };
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try {
                                a.close();
                                closed.complete(null);
                            } catch (Throwable e) {
                                closed.completeExceptionally(e);
                            }
                        });
        try {
            closed.get(3, TimeUnit.SECONDS);
            callback.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertFalse(held.get());
        assertTrue(a.isClosed());
    }

    @Test
    void startupFailureAlsoCleansUpOutsideLifecycleLock() throws Exception {
        var a = f.node(10, (c, m) -> {});
        Object lock = lifecycleLock(a);
        var transport = f.network.transports.get(10);
        transport.failStart = true;
        var held = new AtomicBoolean(true);
        transport.closeHook = () -> held.set(Thread.holdsLock(lock));
        assertThrows(IllegalStateException.class, a::start);
        assertTrue(a.isClosed());
        assertFalse(held.get());
        assertThrows(
                IllegalStateException.class,
                () -> a.timer.newTimeout(t -> {}, 1, TimeUnit.SECONDS));
    }

    @Test
    void providerStartupCallbackCannotReenterCloseUnderStartupLock() throws Exception {
        var nodeRef = new AtomicReference<RpcNode>();
        var rejected = new AtomicReference<Throwable>();
        RpcNetworkProvider provider =
                config -> {
                    var delegate = f.network.create(config);
                    return new RpcTransport() {
                        public io.netty.buffer.ByteBufAllocator allocator() {
                            return delegate.allocator();
                        }

                        public void start(Listener listener) {
                            delegate.start(listener);
                            listener.onWriteFailure(
                                    null, new IllegalStateException("Startup diagnostic"));
                        }

                        public void connect(
                                java.net.InetSocketAddress address,
                                cn.managame.network.ConnectCallback callback) {
                            delegate.connect(address, callback);
                        }

                        public java.net.InetSocketAddress localAddress() {
                            return delegate.localAddress();
                        }

                        public Submission write(Connection c, ByteBuf frame) {
                            return delegate.write(c, frame);
                        }

                        public void close() {
                            delegate.close();
                        }
                    };
                };
        var node =
                f.builder(10, (c, m) -> {})
                        .provider(provider)
                        .diagnostics(
                                d -> {
                                    try {
                                        nodeRef.get().close();
                                    } catch (Throwable failure) {
                                        rejected.set(failure);
                                    }
                                })
                        .build();
        nodeRef.set(node);
        f.nodes.add(node);
        node.start();
        assertInstanceOf(IllegalStateException.class, rejected.get());
        assertTrue(node.isRunning());
        assertFalse(f.network.transports.get(10).closed);
        node.close();
        assertTrue(node.isClosed());
    }

    @Test
    void borrowedTimerIsNotStoppedByCallbackClose() throws Exception {
        var shared =
                new HashedWheelTimer(
                        Thread.ofPlatform().daemon().factory(), 10, TimeUnit.MILLISECONDS);
        try {
            var a = f.builder(10, (c, m) -> {}).timer(shared).build();
            f.nodes.add(a);
            var b = f.node(20, (c, m) -> {});
            f.connect(a, b, 1);
            var closed = new CompletableFuture<Void>();
            a.call(
                    20,
                    1,
                    body("timeout"),
                    RpcOptions.builder().timeout(Duration.ofMillis(50)).build(),
                    r -> {
                        try {
                            a.close();
                            closed.complete(null);
                        } catch (Throwable e) {
                            closed.completeExceptionally(e);
                        }
                    });
            closed.get(3, TimeUnit.SECONDS);
            assertTrue(a.isClosed());
            var tick = new CompletableFuture<Void>();
            shared.newTimeout(t -> tick.complete(null), 1, TimeUnit.MILLISECONDS);
            tick.get(3, TimeUnit.SECONDS);
        } finally {
            shared.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protocolErrorRepliesOnOriginalSlotWithoutSelector(boolean otherSlotBlocked)
            throws Exception {
        var codec =
                new DefaultRpcCodec() {
                    public ByteBuf encode(RpcMessage message) {
                        var frame = super.encode(message);
                        if (message instanceof RpcRequest) frame.setInt(frame.readerIndex() + 1, 0);
                        return frame;
                    }
                };
        var a =
                f.builder(10, (c, m) -> {})
                        .selector((p, k) -> p.connection(1))
                        .codec(codec)
                        .build();
        f.nodes.add(a);
        var selectorCalls = new AtomicInteger();
        var b =
                f.builder(20, (c, m) -> {})
                        .selector(
                                (p, k) -> {
                                    selectorCalls.incrementAndGet();
                                    return p.connection(0);
                                })
                        .build();
        f.nodes.add(b);
        f.connect(a, b, 2);
        ((Network.Conn) b.peer(10).connection(0)).writable = !otherSlotBlocked;
        var written = new AtomicReference<Connection>();
        f.network.transports.get(20).dropFrame =
                (c, frame) -> {
                    if (frame[0] == 2) written.set(c);
                    return false;
                };
        var result = new CompletableFuture<RpcResult>();
        a.call(20, 1, body("bad wire"), RpcOptions.DEFAULT, result::complete);
        assertEquals(RpcError.PROTOCOL_ERROR, result.get(3, TimeUnit.SECONDS).error());
        assertSame(b.peer(10).connection(1), written.get());
        assertEquals(0, selectorCalls.get());
    }
}
