package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

class RpcDefaultCodecTest {
    @Test
    void malformedMetadataPreservesCorrelationWithoutAnExtraHeaderApi() {
        var codec = new DefaultRpcCodec();
        ByteBuf frame =
                codec.encode(new RpcRequest(7, 8, RpcOptions.route(-3), raw(new byte[] {1})));
        ByteBuf input = Unpooled.buffer().writeZero(5).writeBytes(frame);
        try {
            input.readerIndex(5);
            input.setInt(5 + 26, -1);
            var failure = assertThrows(RpcProtocolException.class, () -> codec.decode(input));
            assertEquals(8, failure.requestId());
            assertFalse(failure.isResponse());
            assertEquals(-3, failure.routeKey());
            assertEquals(5, input.readerIndex());
        } finally {
            input.release();
            frame.release();
        }
    }

    @Test
    void routedMessagesKeepAddressesOutsideTheInnerMessage() {
        var codec = new DefaultRpcCodec();
        var metadata = new RpcMetadata().putString((short) 1, "route");
        RpcMessage[] messages = {
            new RpcRequest(
                    1, 7, RpcOptions.builder().metadata(metadata).build(), raw(new byte[] {42})),
            new RpcResponse(7, 0, metadata, raw(new byte[] {42}))
        };
        for (var message : messages) {
            ByteBuf inner = codec.encode(message);
            ByteBuf frame = codec.encode(new RpcRouteMessage(-10, 20, inner));
            try {
                var decoded = (RpcRouteMessage) codec.decode(frame);
                assertEquals(-10, decoded.sourceNodeId());
                assertEquals(20, decoded.targetNodeId());
                assertEquals(inner, decoded.inner());
                var innerMessage = codec.decode(decoded.inner());
                assertEquals(message.getClass(), innerMessage.getClass());
                assertEquals("route", metadata(innerMessage).getString((short) 1));
                assertArrayEquals(new byte[] {42}, bytes(messageBody(innerMessage)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new RpcRouteMessage(1, 2, Unpooled.EMPTY_BUFFER));
            } finally {
                frame.release();
                inner.release();
            }
        }
    }

    private static RpcMetadata metadata(RpcMessage message) {
        return message instanceof RpcRequest q ? q.metadata() : ((RpcResponse) message).metadata();
    }

    private static ByteBuf messageBody(RpcMessage message) {
        return (ByteBuf)
                (message instanceof RpcRequest q ? q.body() : ((RpcResponse) message).body());
    }

    @Test
    void routeCodecDoesNotInspectEvenUnknownInnerBytes() {
        var codec = new DefaultRpcCodec();
        ByteBuf inner = Unpooled.buffer().writeInt(0xdeadbeef);
        ByteBuf frame = codec.encode(new RpcRouteMessage(1, 2, inner));
        try {
            var route = (RpcRouteMessage) codec.decode(frame);
            assertEquals(inner, route.inner());
            assertThrows(RpcProtocolException.class, () -> codec.decode(route.inner()));
            assertEquals(0, inner.readerIndex());
            assertEquals(0, frame.readerIndex());
        } finally {
            frame.release();
            inner.release();
        }
    }

    @Test
    void optionalClientMarkersTravelInMetadataWithoutACustomCodec() {
        var codec = new DefaultRpcCodec();
        var metadata = new RpcMetadata().put((short) 1024, new byte[] {(byte) 0x80, (byte) 0xff});
        RpcMessage[] messages = {
            new RpcRequest(
                    1, 1, RpcOptions.builder().metadata(metadata).build(), raw(new byte[] {42})),
            new RpcResponse(1, 0, metadata, raw(new byte[] {42}))
        };
        for (var message : messages) {
            ByteBuf frame = codec.encode(message);
            try {
                int fixed = message instanceof RpcRequest ? 30 : 13;
                assertEquals(fixed + 5 + 1, frame.readableBytes());
                assertEquals(5, frame.getInt(fixed - 4));
                var decoded = codec.decode(frame);
                assertArrayEquals(
                        new byte[] {(byte) 0x80, (byte) 0xff}, metadata(decoded).get((short) 1024));
                assertArrayEquals(new byte[] {42}, bytes(messageBody(decoded)));
            } finally {
                frame.release();
            }
        }
    }

    @Test
    void defaultRequestCodecBorrowsBodyAndPreservesInputIndices() {
        var codec = new DefaultRpcCodec();
        ByteBuf body = PooledByteBufAllocator.DEFAULT.buffer().writeByte(9).writeInt(123);
        body.readerIndex(1);
        ByteBuf frame = null, prefixed = null;
        try {
            var options =
                    RpcOptions.builder()
                            .routeKey(-3)
                            .busType((byte) 1)
                            .busId(45)
                            .putString((short) 1, "trace")
                            .build();
            frame = codec.encode(new RpcRequest(7, 8, options, body));
            assertEquals(1, body.readerIndex());
            assertEquals(1, body.refCnt());
            prefixed = PooledByteBufAllocator.DEFAULT.buffer().writeZero(5).writeBytes(frame);
            prefixed.readerIndex(5);
            var request = (RpcRequest) codec.decode(prefixed);
            assertEquals(5, prefixed.readerIndex());
            assertEquals(7, request.command());
            assertEquals(8, request.requestId());
            assertEquals(-3, request.routeKey());
            assertEquals(45, request.businessId());
            var decoded = (ByteBuf) request.body();
            assertTrue(decoded.isReadOnly());
            assertEquals(123, decoded.getInt(0));
            assertEquals("trace", request.metadata().getString((short) 1));
            assertTrue(prefixed.release());
            assertEquals(0, decoded.refCnt());
        } finally {
            if (prefixed != null && prefixed.refCnt() > 0) prefixed.release();
            if (frame != null) frame.release();
            body.release();
        }
    }

    @Test
    void responseModelRoundTripsAndErrorsBypassBodySerialization() {
        var codec = new DefaultRpcCodec();
        var metadata = new RpcMetadata().putLong((short) 1, 12);
        var response = new RpcResponse(8, 0, metadata, raw(new byte[] {1, 2}));
        metadata.putInt((short) 2, 3);
        ByteBuf frame = codec.encode(response);
        try {
            var result = (RpcResponse) codec.decode(frame);
            assertArrayEquals(new byte[] {1, 2}, bytes((ByteBuf) result.body()));
            assertFalse(result.metadata().contains((short) 2));
            assertEquals(12, result.metadata().getLong((short) 1));
        } finally {
            frame.release();
        }
        var forbidden = new DefaultRpcCodec();
        frame =
                forbidden.encode(
                        new RpcResponse(8, RpcError.NO_HANDLER.code(), RpcMetadata.EMPTY, null));
        try {
            var result = (RpcResponse) forbidden.decode(frame);
            assertEquals(RpcError.NO_HANDLER.code(), result.errorCode());
            assertNull(result.body());
        } finally {
            frame.release();
        }
        assertThrows(
                RpcProtocolException.class,
                () -> codec.encode(new RpcResponse(8, 1, RpcMetadata.EMPTY, raw(new byte[1]))));
    }

    @Test
    void defaultCodecUsesExistingSharedWireBytes() throws Exception {
        String json = Files.readString(Path.of("../docs/OGBS Game RPC v1 wire vectors.json"));
        var entries =
                Pattern.compile("\"hex\"\\s*:\\s*\"([0-9a-f]+)\"")
                        .matcher(json.substring(0, json.indexOf("\"invalid\"")));
        var codec = new DefaultRpcCodec();
        int tested = 0;
        while (entries.find()) {
            byte[] bytes = HexFormat.of().parseHex(entries.group(1));
            var input = Unpooled.wrappedBuffer(bytes);
            ByteBuf output = null;
            try {
                output = codec.encode(codec.decode(input));
                assertArrayEquals(bytes, ByteBufUtil.getBytes(output));
                assertEquals(0, input.readerIndex());
                tested++;
            } finally {
                if (output != null) output.release();
                input.release();
            }
        }
        assertTrue(tested > 10);
    }

    @Test
    void bodyAndFrameLimitsReleaseTemporaryBuffersOnFailure() {
        var allocated = new ArrayList<ByteBuf>();
        ByteBufAllocator allocator = countingAllocator(allocated);
        var codec = new DefaultRpcCodec(allocator, new RpcLimits(40, 0, 16, 10));
        assertThrows(
                RpcProtocolException.class,
                () -> codec.encode(new RpcRequest(1, 1, RpcOptions.DEFAULT, raw(new byte[17]))));
        assertThrows(
                RpcProtocolException.class,
                () -> codec.encode(new RpcRequest(1, 1, RpcOptions.DEFAULT, raw(new byte[11]))));
        assertTrue(allocated.stream().allMatch(b -> b.refCnt() == 0));
    }

    @Test
    void nodeUsesOneFullCodecForRequestsAndResponses() throws Exception {
        var fixture = new RpcNodeTest();
        var responses = new AtomicInteger();
        var delegate = new DefaultRpcCodec();
        var codec =
                new DefaultRpcCodec() {
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcResponse) responses.incrementAndGet();
                        return delegate.encode(message);
                    }

                    public RpcMessage decode(ByteBuf input) {
                        return delegate.decode(input);
                    }
                };
        try {
            var a =
                    RpcNode.builder()
                            .nodeId(10)
                            .listen("127.0.0.1", 0)
                            .codec(codec)
                            .handler((c, m) -> {})
                            .defaultTimeout(Duration.ofSeconds(2))
                            .provider(fixture.network)
                            .build();
            fixture.nodes.add(a);
            var b =
                    RpcNode.builder()
                            .nodeId(20)
                            .listen("127.0.0.1", 0)
                            .codec(codec)
                            .handler(
                                    fixture.recording(
                                            20,
                                            (c, m) -> fixture.reply((RpcMessage) m, body("ok"))))
                            .defaultTimeout(Duration.ofSeconds(2))
                            .provider(fixture.network)
                            .build();
            fixture.nodes.add(b);
            fixture.connect(a, b, 1);
            assertTrue(fixture.call(a, 20, RpcOptions.DEFAULT).get().isSuccess());
            assertEquals(1, responses.get());
        } finally {
            fixture.close();
        }
    }

    @Test
    void rawBodiesAllocateOnlyTheFinalFrame() {
        var allocated = new ArrayList<ByteBuf>();
        var allocator = countingAllocator(allocated);
        var codec = new DefaultRpcCodec(allocator, RpcLimits.DEFAULT);
        ByteBuf input = Unpooled.buffer().writeByte(9).writeZero(1024);
        input.readerIndex(1);
        try {
            ByteBuf output = codec.encode(new RpcRequest(1, 1, RpcOptions.DEFAULT, input));
            try {
                assertEquals(1, allocated.size());
                assertSame(output, allocated.getFirst());
                assertEquals(30 + 1024, output.readableBytes());
                assertEquals(1, input.readerIndex());
                assertEquals(1, input.refCnt());
            } finally {
                output.release();
            }
            allocated.clear();
            output = codec.encode(new RpcResponse(1, 0, RpcMetadata.EMPTY, raw(new byte[1024])));
            try {
                assertEquals(1, allocated.size());
                assertSame(output, allocated.getFirst());
                assertEquals(13 + 1024, output.readableBytes());
            } finally {
                output.release();
            }
            assertTrue(allocated.stream().allMatch(b -> b.refCnt() == 0));
        } finally {
            input.release();
        }
    }

    private static ByteBufAllocator countingAllocator(List<ByteBuf> allocated) {
        return new AbstractByteBufAllocator(false) {
            protected ByteBuf newHeapBuffer(int initial, int maximum) {
                var buffer = Unpooled.buffer(initial, maximum);
                allocated.add(buffer);
                return buffer;
            }

            protected ByteBuf newDirectBuffer(int initial, int maximum) {
                return newHeapBuffer(initial, maximum);
            }

            public boolean isDirectBufferPooled() {
                return false;
            }
        };
    }
}
