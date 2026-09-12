package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcHotPathTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    static class CountingCodec extends DefaultRpcCodec {
        int encoded;

        CountingCodec() {
            super(ALLOCATOR, LIMITS);
        }

        @Override
        public ByteBuf encode(RpcMessage message) {
            if (!(message instanceof RpcHandshake)) encoded++;
            return super.encode(message);
        }
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void knownBackpressureAndUnknownPeerRejectWithoutEncoding() throws Exception {
        var codec = new CountingCodec();
        var a = fixture.builder(10, (c, m) -> {}).codec(codec).build();
        fixture.nodes.add(a);
        var router = fixture.node(20, (c, m) -> {});
        fixture.connect(a, router, 2);
        fixture.network.transports.get(10).dropBusiness = true;
        assertTrue(a.send(20, 1, body("warm"), RpcOptions.DEFAULT));
        var options = RpcOptions.route(123);
        var fixed = (Network.Conn) ConnectionSelector.DEFAULT.select(a.peer(20), 123);
        fixed.writable = false;
        codec.encoded = 0;
        assertFalse(a.send(20, 1, body("blocked"), options));
        assertEquals(RpcError.OVERLOADED, fixture.call(a, 20, options).get().error());
        assertFalse(a.send(99, 1, body("unrouted"), RpcOptions.DEFAULT));
        assertEquals(0, codec.encoded);
        assertEquals(0, RpcException.OVERLOADED.getStackTrace().length);
        assertTrue(a.send(20, 1, body("other slot"), RpcOptions.DEFAULT));
        assertEquals(1, codec.encoded);
    }

    @Test
    void directTrafficDoesNotWaitForUnrelatedHandshakeAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var a =
                fixture.builder(10, (c, m) -> {})
                        .peerAdmission(
                                id -> {
                                    if (id == 50) block(entered, release);
                                    return true;
                                })
                        .build();
        fixture.nodes.add(a);
        var router = fixture.node(20, (c, m) -> {});
        fixture.connect(a, router, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        assertTrue(a.send(20, 1, body("warm"), RpcOptions.DEFAULT));
        var incoming = fixture.network.new Conn(fixture.network.transports.get(10), true);
        incoming.handler.onConnected(incoming);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var admission =
                        workers.submit(
                                () -> {
                                    var hello =
                                            new DefaultRpcCodec(ALLOCATOR, LIMITS)
                                                    .encode(
                                                            new RpcHandshake(
                                                                    RpcHandshake.Kind.HELLO,
                                                                    50,
                                                                    10,
                                                                    0,
                                                                    1));
                                    try {
                                        incoming.handler.onMessage(incoming, hello);
                                    } catch (Exception e) {
                                        throw new AssertionError(e);
                                    } finally {
                                        hello.release();
                                    }
                                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(
                        workers.submit(() -> a.send(20, 1, body("still sends"), RpcOptions.DEFAULT))
                                .get(1, TimeUnit.SECONDS));
                release.countDown();
                admission.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            incoming.close();
        }
    }

    @Test
    void routeFrameRetainsInnerWithoutCopyingAndOwnsItsLifetime() {
        var codec = new DefaultRpcCodec();
        var inner =
                PooledByteBufAllocator.DEFAULT.buffer().writeZero(3).writeInt(123).writeZero(65536);
        inner.readerIndex(3);
        ByteBuf frame = null;
        try {
            frame = codec.encode(new RpcRouteMessage(10, 20, inner));
            var composite = assertInstanceOf(CompositeByteBuf.class, frame);
            assertEquals(2, composite.numComponents());
            assertEquals(9, composite.component(0).readableBytes());
            assertEquals(inner.memoryAddress() + 3, composite.component(1).memoryAddress());
            assertEquals(2, inner.refCnt());
            assertEquals(3, inner.readerIndex());
            inner.release();
            assertEquals(123, frame.getInt(9));
            frame.release();
            frame = null;
            assertEquals(0, inner.refCnt());
        } finally {
            if (frame != null) frame.release();
            if (inner.refCnt() > 0) inner.release();
        }
    }
}
