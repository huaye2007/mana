package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicReference;

class RpcRawMessageTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void requestBodyIsBorrowedAndMayBeRetainedByTheUser() throws Exception {
        var borrowed = new AtomicReference<ByteBuf>();
        var held = new AtomicReference<ByteBuf>();
        var metadata = new AtomicReference<RpcMetadata>();
        var b =
                fixture.node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            assertEquals(123456, q.command());
                            assertEquals(0, q.requestId());
                            assertTrue(q.body().isReadOnly());
                            borrowed.set(q.body());
                            held.set(q.body().retainedDuplicate());
                            metadata.set(q.metadata());
                        });
        var a = fixture.node(10, (c, m) -> {});
        fixture.connect(a, b, 1);
        ByteBuf frame =
                new DefaultRpcCodec()
                        .encode(
                                new RpcRequest(
                                        123456,
                                        0,
                                        RpcOptions.builder().putLong((short) 1024, 99).build(),
                                        raw(new byte[] {(byte) 0xff, 0})));
        try {
            ((Network.Conn) b.peer(10).connection(0))
                    .handler.onMessage(b.peer(10).connection(0), frame);
            assertEquals(2, frame.refCnt(), "Only the user's retain adds a reference");
            frame.release();
            assertArrayEquals(new byte[] {(byte) 0xff, 0}, bytes(held.get()));
            assertEquals(99, metadata.get().getLong((short) 1024));
        } finally {
            if (held.get() != null) held.get().release();
            if (frame.refCnt() > 0) frame.release();
        }
        assertEquals(0, borrowed.get().refCnt());
    }

    @Test
    void callbackReceivesFullBorrowedResponseAndMayCopyIt() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        var borrowed = new AtomicReference<RpcResponse>();
        var copied = new AtomicReference<ByteBuf>();
        a.call(
                20,
                999,
                body("request"),
                RpcOptions.DEFAULT,
                result -> {
                    assertTrue(result.isSuccess());
                    borrowed.set(result.value());
                    copied.set(result.value().body().copy());
                });
        int id = a.peer(20).pendingSnapshot().getFirst().requestId;
        ByteBuf frame =
                new DefaultRpcCodec()
                        .encode(
                                new RpcResponse(
                                        id,
                                        0,
                                        new RpcMetadata().putInt((short) 1025, 42),
                                        raw(new byte[] {0, (byte) 0xfe})));
        try {
            ((Network.Conn) a.peer(20).connection(0))
                    .handler.onMessage(a.peer(20).connection(0), frame);
            assertNotNull(borrowed.get());
            assertEquals(id, borrowed.get().requestId());
            assertEquals(42, borrowed.get().metadata().getInt((short) 1025));
            assertEquals(1, frame.refCnt(), "RPC must not copy or retain a callback body");
            assertEquals(0, a.peer(20).pendingCount());
            frame.release();
            assertEquals(0, borrowed.get().body().refCnt());
            assertArrayEquals(new byte[] {0, (byte) 0xfe}, bytes(copied.get()));
        } finally {
            if (copied.get() != null) copied.get().release();
            if (frame.refCnt() > 0) frame.release();
        }
    }
}
