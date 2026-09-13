package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.protocol.RpcHeartbeat;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class RpcHeartbeatTest {
    final RpcNodeTest fixture = new RpcNodeTest();
    final AtomicInteger delivered = new AtomicInteger();

    RpcNode node(int id) {
        return node(id, null);
    }

    RpcNode node(int id, RpcCodec codec) {
        var builder =
                fixture.builder(id, (c, m) -> delivered.incrementAndGet())
                        .heartbeatInterval(Duration.ofMillis(100))
                        .heartbeatTimeout(Duration.ofMillis(400));
        if (codec != null) builder.codec(codec);
        var node = builder.build();
        fixture.nodes.add(node);
        return node;
    }

    @AfterEach
    void close() {
        fixture.nodes.forEach(RpcNode::close);
    }

    long count(int node, int type) {
        return fixture.network.transports.get(node).sent.stream().filter(f -> f[0] == type).count();
    }

    @Test
    void readIdleEventsDoNotCloseConnectionsByDefault() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 1);
        var incoming = (Network.Conn) b.peer(10).connection(0);
        incoming.handler.onIdle(incoming, cn.managame.network.IdleType.READ);
        incoming.handler.onIdle(incoming, cn.managame.network.IdleType.ALL);
        assertTrue(incoming.isActive());
        assertTrue(b.peer(10).isReady());
        assertEquals(0L, b.eventCounts().getOrDefault("read-idle", 0L));
        assertEquals(Duration.ZERO, fixture.network.transports.get(20).config.readIdleTimeout());
    }

    @Test
    void inboundReadIdleClosesOnlyItsPhysicalSlotAndIgnoresWriteIdle() throws Exception {
        var a = node(10);
        var b =
                fixture.builder(20, (c, m) -> delivered.incrementAndGet())
                        .readIdleTimeout(Duration.ofSeconds(30))
                        .build();
        fixture.nodes.add(b);
        fixture.connect(a, b, 2);
        var old = (Network.Conn) b.peer(10).connection(0);
        var other = b.peer(10).connection(1);
        var outgoing = (Network.Conn) a.peer(20).connection(0);
        outgoing.handler.onIdle(outgoing, cn.managame.network.IdleType.READ);
        old.handler.onIdle(old, cn.managame.network.IdleType.WRITE);
        assertTrue(old.isActive());
        old.handler.onIdle(old, cn.managame.network.IdleType.READ);
        assertFalse(old.isActive());
        assertTrue(other.isActive());
        assertEquals(1L, b.eventCounts().get("read-idle"));
        await(() -> b.peer(10).isReady());
        var replacement = b.peer(10).connection(0);
        old.handler.onIdle(old, cn.managame.network.IdleType.READ);
        assertTrue(replacement.isActive());
        assertEquals(1L, b.eventCounts().get("read-idle"));
    }

    @Test
    void fixedWireRoundTripAndValidation() {
        var codec = new DefaultRpcCodec();
        for (var kind : RpcHeartbeat.Kind.values()) {
            var message = new RpcHeartbeat(kind, Integer.MAX_VALUE);
            ByteBuf frame = codec.encode(message);
            try {
                assertEquals(5, frame.readableBytes());
                assertEquals(kind == RpcHeartbeat.Kind.PING ? 7 : 8, frame.getByte(0));
                assertEquals(message, codec.decode(frame));
                assertEquals(0, frame.readerIndex());
            } finally {
                frame.release();
            }
        }
        for (int value : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(
                    RpcProtocolException.class,
                    () -> new RpcHeartbeat(RpcHeartbeat.Kind.PING, value));
            var invalid = raw(new byte[] {7, 0, 0, 0, 0});
            invalid.setInt(1, value);
            assertThrows(RpcProtocolException.class, () -> codec.decode(invalid));
        }
        assertThrows(RpcProtocolException.class, () -> codec.decode(raw(new byte[] {7})));
        assertThrows(
                RpcProtocolException.class, () -> codec.decode(raw(new byte[] {8, 0, 0, 0, 1, 0})));
    }

    @Test
    void onlyInitiatorProbesEverySlotAndSynchronousPongDoesNotCreateCalls() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 2);
        var first = a.peer(20).connection(0);
        var second = a.peer(20).connection(1);
        await(() -> count(10, 7) >= 6 && count(20, 8) >= 6);
        assertEquals(0, count(20, 7));
        assertEquals(0, count(10, 8));
        assertSame(first, a.peer(20).connection(0));
        assertSame(second, a.peer(20).connection(1));
        assertEquals(0, delivered.get());
        assertEquals(0, a.calls.requestIdCounter.get());
        assertEquals(0, a.peer(20).pendingCount());
        assertThrows(
                IllegalArgumentException.class,
                () -> a.send(20, new RpcHeartbeat(RpcHeartbeat.Kind.PING, 1), RpcOptions.DEFAULT));
    }

    @Test
    void missingPongReplacesOnlyFailedPhysicalConnectionAndKeepsPeer() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 2);
        var peer = a.peer(20);
        var old = (Network.Conn) peer.connection(0);
        var healthy = peer.connection(1);
        fixture.network.transports.get(20).dropFrame = (c, f) -> c == old.other && f[0] == 8;
        await(() -> !old.isActive());
        await(() -> peer.isReady() && peer.connection(0) != old);
        assertSame(peer, a.peer(20));
        assertSame(healthy, peer.connection(1));
        assertNotEquals(old.id(), peer.connection(0).id());
        assertTrue(a.eventCounts().getOrDefault("heartbeat-timeout", 0L) >= 1);
        // A late reply on the retired connection cannot affect its replacement.
        old.handler.onMessage(old, raw(new byte[] {8, 0, 0, 0, 1}));
        assertTrue(peer.isReady());
    }

    @Test
    void wrongPongAndBusinessTrafficCannotAcknowledgeProbe() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 1);
        var old = (Network.Conn) a.peer(20).connection(0);
        fixture.network.transports.get(20).dropFrame = (c, f) -> f[0] == 8;
        await(() -> count(10, 7) > 0);
        var codec = new DefaultRpcCodec();
        var request = codec.encode(new RpcRequest(1, 0, RpcOptions.DEFAULT, body("traffic")));
        try {
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (old.isActive() && System.nanoTime() < until) {
                old.handler.onMessage(old, raw(new byte[] {8, 0, 0, 0, 99}));
                if (old.isActive()) old.handler.onMessage(old, request);
                Thread.sleep(20);
            }
        } finally {
            request.release();
        }
        assertFalse(old.isActive());
        assertTrue(delivered.get() > 0);
    }

    @Test
    void backpressureUsesDeadlineWithoutTryingAnotherSlot() throws Exception {
        var a = node(10);
        var b =
                fixture.builder(20, (c, m) -> delivered.incrementAndGet())
                        .readIdleTimeout(Duration.ofSeconds(30))
                        .build();
        fixture.nodes.add(b);
        fixture.connect(a, b, 2);
        var old = (Network.Conn) a.peer(20).connection(0);
        var healthy = a.peer(20).connection(1);
        old.writable = false;
        Thread.sleep(250);
        assertTrue(old.isActive(), "Backpressure must not close immediately");
        await(() -> !old.isActive());
        assertSame(healthy, a.peer(20).connection(1));
    }

    @Test
    void removalCancelsProbesAndReconnects() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 1);
        await(() -> count(10, 7) > 0);
        a.removePeer(20);
        long count = count(10, 7);
        Thread.sleep(700);
        assertEquals(count, count(10, 7));
        assertNull(a.peer(20));
        assertTrue(fixture.network.transports.get(10).connections.isEmpty());
    }

    @Test
    void closeCancelsProbes() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 1);
        await(() -> count(10, 7) > 0);
        a.close();
        long count = count(10, 7);
        Thread.sleep(700);
        assertEquals(count, count(10, 7));
    }

    @Test
    void codecFailureClosesAndRetries() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var codec =
                new DefaultRpcCodec() {
                    @Override
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcHeartbeat && fail.getAndSet(false))
                            throw new IllegalStateException("probe encode failed");
                        return super.encode(message);
                    }
                };
        var a = node(10, codec);
        var b = node(20);
        fixture.connect(a, b, 1);
        var old = a.peer(20).connection(0);
        await(() -> !old.isActive());
        await(() -> a.peer(20).isReady() && count(20, 8) > 0);
        assertTrue(a.eventCounts().getOrDefault("heartbeat-failed", 0L) >= 1);
    }

    @Test
    void acceptorCannotInitiateHeartbeat() throws Exception {
        var a = node(10);
        var b = node(20);
        fixture.connect(a, b, 1);
        var old = (Network.Conn) a.peer(20).connection(0);
        old.handler.onMessage(old, raw(new byte[] {7, 0, 0, 0, 1}));
        assertFalse(old.isActive());
    }

    @Test
    void stalledCodecDoesNotBlockDeadlineAndCannotSendAfterReconnect() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var codec =
                new DefaultRpcCodec() {
                    @Override
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcHeartbeat && first.getAndSet(false)) {
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                        return super.encode(message);
                    }
                };
        var a = node(10, codec);
        var b = node(20);
        fixture.connect(a, b, 1);
        var old = a.peer(20).connection(0);
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            await(() -> !old.isActive());
            await(() -> a.peer(20).isReady());
            assertNotSame(old, a.peer(20).connection(0));
        } finally {
            release.countDown();
        }
        await(() -> count(20, 8) >= 2);
        assertTrue(a.peer(20).isReady());
    }

    @Test
    void selfConnectionUsesOnlyOutgoingEndForProbes() throws Exception {
        var a = node(10);
        a.start();
        a.connect(10, "127.0.0.1", a.localAddress().getPort());
        await(() -> a.peer(10) != null && a.peer(10).isReady());
        var original = a.peer(10).connection(0);
        await(() -> count(10, 8) >= 3);
        assertSame(original, a.peer(10).connection(0));
        assertEquals(0, delivered.get());
        assertEquals(0L, a.eventCounts().getOrDefault("heartbeat-timeout", 0L));
    }

    @Test
    void heartbeatConfigurationRejectsSubTickAndInvalidDurations() {
        for (var value :
                new Duration[] {Duration.ZERO, Duration.ofMillis(99), Duration.ofSeconds(-1)}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.builder(10, (c, m) -> {}).heartbeatInterval(value).build());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.builder(10, (c, m) -> {}).heartbeatTimeout(value).build());
        }
    }
}
