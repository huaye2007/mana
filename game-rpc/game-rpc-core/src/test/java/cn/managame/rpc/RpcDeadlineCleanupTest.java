package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcDeadlineCleanupTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS), "Not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    enum HandshakeDelay {
        BEFORE_HELLO,
        ADMISSION,
        ACK_ENCODE
    }

    @ParameterizedTest
    @EnumSource(HandshakeDelay.class)
    void delayedTimeWheelCannotPublishAnExpiredIncomingHandshake(HandshakeDelay delay)
            throws Exception {
        var timerEntered = new CountDownLatch(1);
        var timerRelease = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var codec =
                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                    @Override
                    public ByteBuf encode(RpcMessage message) {
                        if (delay == HandshakeDelay.ACK_ENCODE
                                && message instanceof RpcHandshake h
                                && h.kind() == RpcHandshake.Kind.ACK) block(entered, release);
                        return super.encode(message);
                    }
                };
        var a =
                fixture.builder(10, (c, m) -> fail("Unverified connection delivered a message"))
                        .codec(codec)
                        .peerAdmission(
                                id -> {
                                    if (delay == HandshakeDelay.ADMISSION) block(entered, release);
                                    return true;
                                })
                        .build();
        fixture.nodes.add(a);
        a.start();
        a.timer.newTimeout(t -> block(timerEntered, timerRelease), 0, TimeUnit.MILLISECONDS);
        assertTrue(timerEntered.await(2, TimeUnit.SECONDS));
        var incoming = fixture.network.new Conn(fixture.network.transports.get(10), true);
        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                incoming.handler.onConnected(incoming);
                var hello = codec.encode(new RpcHandshake(RpcHandshake.Kind.HELLO, 20, 10, 0, 1));
                if (delay == HandshakeDelay.BEFORE_HELLO) Thread.sleep(350);
                var handshake =
                        worker.submit(
                                () -> {
                                    try {
                                        incoming.handler.onMessage(incoming, hello);
                                    } catch (Exception e) {
                                        throw new AssertionError(e);
                                    }
                                });
                if (delay != HandshakeDelay.BEFORE_HELLO) {
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    Thread.sleep(350);
                    release.countDown();
                }
                handshake.get(2, TimeUnit.SECONDS);
                assertFalse(incoming.isActive());
                assertTrue(a.peer(20) == null || !a.peer(20).isReady());
                assertEquals(0L, a.eventCounts().getOrDefault("connection-ready", 0L));
                assertEquals(1L, a.eventCounts().getOrDefault("handshake-timeout", 0L));
            } finally {
                release.countDown();
                timerRelease.countDown();
                incoming.close();
            }
        }
    }

    @Test
    void lateAckCannotCompleteOutgoingConnectWhenTheTimeWheelIsDelayed() throws Exception {
        var timerEntered = new CountDownLatch(1);
        var timerRelease = new CountDownLatch(1);
        var ackEntered = new CountDownLatch(1);
        var ackRelease = new CountDownLatch(1);
        var node = new AtomicReference<RpcNode>();
        var a =
                fixture.builder(10, (c, m) -> {})
                        .handshakeTimeout(Duration.ofMillis(500))
                        .reconnectDelay(Duration.ofDays(1))
                        .codec(
                                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                                    @Override
                                    public ByteBuf encode(RpcMessage message) {
                                        if (message instanceof RpcHandshake h
                                                && h.kind() == RpcHandshake.Kind.HELLO) {
                                            node.get()
                                                    .timer
                                                    .newTimeout(
                                                            t -> block(timerEntered, timerRelease),
                                                            0,
                                                            TimeUnit.MILLISECONDS);
                                            try {
                                                assertTrue(timerEntered.await(2, TimeUnit.SECONDS));
                                            } catch (InterruptedException e) {
                                                throw new AssertionError(e);
                                            }
                                        }
                                        return super.encode(message);
                                    }
                                })
                        .build();
        node.set(a);
        fixture.nodes.add(a);
        var b =
                fixture.builder(20, (c, m) -> {})
                        .handshakeTimeout(Duration.ofSeconds(5))
                        .codec(
                                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                                    @Override
                                    public ByteBuf encode(RpcMessage message) {
                                        if (message instanceof RpcHandshake h
                                                && h.kind() == RpcHandshake.Kind.ACK)
                                            block(ackEntered, ackRelease);
                                        return super.encode(message);
                                    }
                                })
                        .build();
        fixture.nodes.add(b);
        b.start();
        a.start();
        var connected = connectResult(a, 20, "127.0.0.1", b.localAddress().getPort());
        try {
            assertTrue(ackEntered.await(2, TimeUnit.SECONDS));
            Thread.sleep(600);
            ackRelease.countDown();
            await(() -> a.eventCounts().getOrDefault("handshake-timeout", 0L) == 1);
            assertFalse(a.peer(20).isReady());
            assertFalse(connected.isDone(), "A late ACK must not report connect success");
            assertEquals(0L, a.eventCounts().getOrDefault("connection-ready", 0L));
        } finally {
            ackRelease.countDown();
            timerRelease.countDown();
        }
    }
}
