package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.concurrent.atomic.*;

class RpcErrorArgsTest {
    final DefaultRpcCodec codec = new DefaultRpcCodec();

    @Test
    void roundTripPreservesUnicodeEmptyArgumentsMetadataAndInputIndices() {
        var metadata = RpcMetadata.builder().putLong((short) 1024, 123L).build();
        var original = RpcResponse.error(17, 10001, metadata, "1000", "", "金币😀");
        var encoded = codec.encode(original);
        var input = PooledByteBufAllocator.DEFAULT.buffer().writeByte(99).writeBytes(encoded);
        encoded.release();
        input.readerIndex(1);
        RpcResponse decoded;
        try {
            int end = input.writerIndex();
            decoded = (RpcResponse) codec.decode(input);
            assertEquals(1, input.readerIndex());
            assertEquals(end, input.writerIndex());
            assertEquals(original.requestId(), decoded.requestId());
            assertEquals(original.errorCode(), decoded.errorCode());
            assertEquals(original.metadata().encoded(), decoded.metadata().encoded());
            assertArrayEquals(original.errorArgs(), decoded.errorArgs());
            assertNull(decoded.body());
        } finally {
            input.release();
        }
        assertArrayEquals(new String[] {"1000", "", "金币😀"}, decoded.errorArgs());
        assertEquals(123L, decoded.metadata().getLong((short) 1024));
    }

    @Test
    void arraysAreDefensivelyCopiedAndNullArgumentsAreRejected() {
        var args = new String[] {"a"};
        var response = RpcResponse.error(1, 10001, args);
        args[0] = "changed";
        response.errorArgs()[0] = "changed again";
        assertArrayEquals(new String[] {"a"}, response.errorArgs());
        var same = RpcResponse.error(1, 10001, "a");
        assertEquals(response, same);
        assertEquals(response.hashCode(), same.hashCode());
        assertThrows(
                NullPointerException.class, () -> RpcResponse.error(1, 10001, (String[]) null));
        assertThrows(
                NullPointerException.class, () -> RpcResponse.error(1, 10001, new String[] {null}));
        assertThrows(IllegalArgumentException.class, () -> RpcResponse.error(1, 0));
        assertThrows(IllegalArgumentException.class, () -> RpcResponse.error(1, -1));
    }

    @Test
    void emptyArrayKeepsOldWireButOneEmptyStringHasLengthPrefix() {
        var empty = codec.encode(RpcResponse.error(1, 10001));
        var one = codec.encode(RpcResponse.error(1, 10001, ""));
        try {
            assertEquals("02000000010000271100000000", ByteBufUtil.hexDump(empty));
            assertEquals("0200000001000027110000000000000000", ByteBufUtil.hexDump(one));
            assertArrayEquals(new String[0], ((RpcResponse) codec.decode(empty)).errorArgs());
            assertArrayEquals(new String[] {""}, ((RpcResponse) codec.decode(one)).errorArgs());
        } finally {
            empty.release();
            one.release();
        }
    }

    @Test
    void successfulRawBodyRemainsOpaque() {
        var response = new RpcResponse(1, 0, RpcMetadata.EMPTY, raw(new byte[] {-1, 0}));
        var frame = codec.encode(response);
        try {
            var decoded = (RpcResponse) codec.decode(frame);
            assertEquals(response.body(), decoded.body());
            assertEquals(0, decoded.errorArgs().length);
        } finally {
            frame.release();
        }
        assertThrows(
                RpcProtocolException.class,
                () ->
                        codec.encode(
                                new RpcResponse(
                                        1,
                                        0,
                                        RpcMetadata.EMPTY,
                                        body("ok"),
                                        new String[] {"bad"})));
        assertThrows(
                RpcProtocolException.class,
                () ->
                        codec.encode(
                                new RpcResponse(
                                        1,
                                        10001,
                                        RpcMetadata.EMPTY,
                                        body("bad"),
                                        new String[] {"arg"})));
    }

    @Test
    void malformedArgumentLengthsAndUtf8AreCorrelatedProtocolErrors() {
        for (String payload :
                new String[] {
                    "00",
                    "ffffffff",
                    "7fffffff",
                    "0000000261",
                    "0000000000",
                    "00000002c080",
                    "00000003eda080",
                    "00000004f4908080",
                    "00000002e4b8"
                }) {
            var frame =
                    Unpooled.buffer()
                            .writeByte(2)
                            .writeInt(17)
                            .writeInt(10001)
                            .writeInt(0)
                            .writeBytes(HexFormat.of().parseHex(payload));
            try {
                var error =
                        assertThrows(
                                RpcProtocolException.class, () -> codec.decode(frame), payload);
                assertEquals(17, error.requestId());
                assertTrue(error.isResponse());
            } finally {
                frame.release();
            }
        }
    }

    @Test
    void errorPayloadLimitIncludesPrefixesAndUtf8Bytes() {
        var limited =
                new DefaultRpcCodec(PooledByteBufAllocator.DEFAULT, new RpcLimits(64, 0, 7, 1));
        var exact = limited.encode(RpcResponse.error(1, 10001, "中"));
        try {
            assertArrayEquals(
                    new String[] {"中"}, ((RpcResponse) limited.decode(exact)).errorArgs());
        } finally {
            exact.release();
        }
        assertThrows(
                RpcProtocolException.class,
                () -> limited.encode(RpcResponse.error(1, 10001, "😀")));
        assertThrows(
                RpcProtocolException.class,
                () -> limited.encode(RpcResponse.error(1, 10001, "", "")));
        var tooLong = codec.encode(RpcResponse.error(1, 10001, "😀"));
        try {
            assertThrows(RpcProtocolException.class, () -> limited.decode(tooLong));
        } finally {
            tooLong.release();
        }
        var noBody =
                new DefaultRpcCodec(PooledByteBufAllocator.DEFAULT, new RpcLimits(64, 0, 0, 1));
        noBody.encode(RpcResponse.error(1, 10001)).release();
        assertThrows(
                RpcProtocolException.class, () -> noBody.encode(RpcResponse.error(1, 10001, "")));
    }

    @Test
    void totalFrameLimitAlsoAppliesToErrorArguments() {
        var limited =
                new DefaultRpcCodec(PooledByteBufAllocator.DEFAULT, new RpcLimits(40, 0, 40, 1));
        limited.encode(RpcResponse.error(1, 10001, "a".repeat(23))).release();
        assertThrows(
                RpcProtocolException.class,
                () -> limited.encode(RpcResponse.error(1, 10001, "a".repeat(24))));
    }

    @Test
    void invalidUnicodeIsRejectedBeforeEncoding() {
        for (String value : new String[] {"\ud800", "\udc00", "\ud800a"}) {
            assertThrows(
                    RpcProtocolException.class,
                    () -> codec.encode(RpcResponse.error(1, 10001, value)));
        }
    }

    @Test
    void customCodecCannotBypassSharedArgumentValidation() {
        for (String value : new String[] {"a".repeat(10), "\ud800"}) {
            var response = RpcResponse.error(1, 10001, value);
            var calls = new AtomicInteger();
            RpcCodec custom =
                    new RpcCodec() {
                        public ByteBuf encode(RpcMessage message) {
                            calls.incrementAndGet();
                            return Unpooled.buffer().writeByte(1);
                        }

                        public RpcMessage decode(ByteBuf frame) {
                            return response;
                        }
                    };
            var handler = new RpcCodecHandler(custom, new RpcLimits(64, 0, 7, 1));
            assertThrows(RpcProtocolException.class, () -> handler.encode(response));
            assertEquals(0, calls.get());
            assertThrows(RpcProtocolException.class, () -> handler.decode(Unpooled.EMPTY_BUFFER));
        }
    }

    @Test
    void resultsDistinguishSuccessRemoteErrorsAndLocalFailures() {
        var success =
                RpcResult.success(new RpcResponse(1, 0, RpcMetadata.EMPTY, Unpooled.EMPTY_BUFFER));
        assertTrue(success.isSuccess());
        assertEquals(0, success.errorCode());
        var business = RpcResponse.error(1, Integer.MAX_VALUE, "arg");
        var remote = RpcResult.received(business);
        assertFalse(remote.isSuccess());
        assertSame(business, remote.value());
        assertEquals(Integer.MAX_VALUE, remote.errorCode());
        assertEquals(Integer.MAX_VALUE, remote.error().code());
        var framework =
                RpcResult.received(RpcResponse.error(1, RpcError.NO_HANDLER.code(), "handler"));
        assertFalse(framework.isSuccess());
        assertEquals(RpcError.NO_HANDLER, framework.error());
        assertArrayEquals(new String[] {"handler"}, framework.value().errorArgs());
        var local = RpcResult.failure(RpcError.TIMEOUT);
        assertFalse(local.isSuccess());
        assertNull(local.value());
        assertEquals(RpcError.TIMEOUT.code(), local.errorCode());
        assertThrows(IllegalArgumentException.class, () -> RpcResult.success(business));
        assertThrows(NullPointerException.class, () -> new RpcResult(null, null));
    }

    @Test
    void duplicateBusinessErrorsNotifyOnceWithCompleteResponse() throws Exception {
        var fixture = new RpcNodeTest();
        try {
            var result = new AtomicReference<RpcResult>();
            var count = new AtomicInteger();
            var a = fixture.node(10, (c, m) -> {});
            var b =
                    fixture.node(
                            20,
                            (c, m) -> {
                                var q = (RpcRequest) m;
                                var response =
                                        RpcResponse.error(q.requestId(), 10001, "1000", "300");
                                assertTrue(fixture.receiver(q).reply(c, response));
                                assertTrue(fixture.receiver(q).reply(c, response));
                            });
            fixture.connect(a, b, 1);
            a.call(
                    20,
                    1,
                    body("request"),
                    RpcOptions.DEFAULT,
                    r -> {
                        result.set(r);
                        count.incrementAndGet();
                    });
            assertEquals(1, count.get());
            assertFalse(result.get().isSuccess());
            assertEquals(10001, result.get().errorCode());
            assertArrayEquals(new String[] {"1000", "300"}, result.get().value().errorArgs());
            assertEquals(0, a.peer(20).pendingCount());
            assertEquals(1L, a.eventCounts().get("call-failure"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void malformedErrorArgumentsCompletePendingOnceWithoutReplyLoop() throws Exception {
        var fixture = new RpcNodeTest();
        try {
            var a = fixture.node(10, (c, m) -> {});
            var b = fixture.node(20, (c, m) -> {});
            fixture.connect(a, b, 1);
            fixture.network.transports.get(10).dropBusiness = true;
            var result = new AtomicReference<RpcResult>();
            var count = new AtomicInteger();
            a.call(
                    20,
                    1,
                    body("request"),
                    RpcOptions.DEFAULT,
                    r -> {
                        result.set(r);
                        count.incrementAndGet();
                    });
            int id = a.peer(20).pendingSnapshot().getFirst().requestId;
            var frame =
                    Unpooled.buffer()
                            .writeByte(2)
                            .writeInt(id)
                            .writeInt(10001)
                            .writeInt(0)
                            .writeInt(-1);
            var connection = (Network.Conn) a.peer(20).connection(0);
            int sent = fixture.network.transports.get(10).sent.size();
            try {
                connection.handler.onMessage(connection, frame);
                connection.handler.onMessage(connection, frame);
            } finally {
                frame.release();
            }
            assertEquals(1, count.get());
            assertEquals(RpcError.PROTOCOL_ERROR, result.get().error());
            assertNull(result.get().value());
            assertEquals(0, a.peer(20).pendingCount());
            assertEquals(sent, fixture.network.transports.get(10).sent.size());
        } finally {
            fixture.close();
        }
    }
}
