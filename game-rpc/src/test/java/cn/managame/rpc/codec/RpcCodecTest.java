package cn.managame.rpc.codec;

import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import cn.managame.rpc.protocol.Metadata;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.support.StringTestSerializer;
import cn.managame.serialization.SerializationType;
import cn.managame.serialization.SerializerManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcCodecTest {

    private static final byte SERIAL = SerializationType.JSON.typeId();

    private static RpcCodec codec() {
        SerializerManager serializerManager = new SerializerManager();
        serializerManager.register(new StringTestSerializer());
        return new RpcCodec(serializerManager);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(Object msg) {
        EmbeddedChannel encodeChannel = new EmbeddedChannel();
        codec().configure(encodeChannel.pipeline());
        encodeChannel.writeOutbound(msg);
        ByteBuf encoded = encodeChannel.readOutbound();
        assertNotNull(encoded);

        EmbeddedChannel decodeChannel = new EmbeddedChannel();
        codec().configure(decodeChannel.pipeline());
        decodeChannel.writeInbound(encoded);
        return (T) decodeChannel.readInbound();
    }

    private static ByteBuf encode(Object msg) {
        EmbeddedChannel encodeChannel = new EmbeddedChannel();
        codec().configure(encodeChannel.pipeline());
        encodeChannel.writeOutbound(msg);
        ByteBuf encoded = encodeChannel.readOutbound();
        assertNotNull(encoded);
        return encoded;
    }

    @Test
    void requestRoundTripKeepsAllFieldsAndMetadata() {
        Metadata[] metadata = {Metadata.ofString((short) 100, "zone-3"), Metadata.ofLong((short) 101, 42L)};
        RpcRequest request = RpcRequest.of(7).requestId(123L).routeKey(99L).busType((byte) 2)
                .busId(888L).serialType(SERIAL).body("hello").metadata(metadata);

        RpcRequest decoded = roundTrip(request);

        assertNotNull(decoded);
        assertFalse(decoded.isOneway());
        assertEquals(123L, decoded.getRequestId());
        assertEquals(7, decoded.getCommand());
        assertEquals(99L, decoded.getRouteKey());
        assertEquals((byte) 2, decoded.getBusType());
        assertEquals(888L, decoded.getBusId());
        assertEquals(SERIAL, decoded.getSerialType());
        assertEquals(2, decoded.getMetadata().length);
        assertEquals("zone-3", decoded.getMetadata()[0].getStrVal());
        assertEquals(42L, decoded.getMetadata()[1].getLval());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), (byte[]) decoded.getBody());
    }

    @Test
    void onewayRoundTripCarriesNoRequestId() {
        RpcRequest decoded = roundTrip(RpcRequest.oneway(5).requestId(999L) // requestId 不上线路
                .routeKey(11L).serialType(SERIAL).body("fire"));

        assertNotNull(decoded);
        assertTrue(decoded.isOneway());
        assertEquals(0L, decoded.getRequestId());
        assertEquals(5, decoded.getCommand());
        assertEquals(11L, decoded.getRouteKey());
        assertNull(decoded.getMetadata());
        assertArrayEquals("fire".getBytes(StandardCharsets.UTF_8), (byte[]) decoded.getBody());
    }

    @Test
    void responseRoundTripKeepsRequestIdAndCode() {
        RpcResponse decoded = roundTrip(new RpcResponse(456L, 3, SERIAL,
                "oops".getBytes(StandardCharsets.UTF_8), null));

        assertNotNull(decoded);
        assertEquals(456L, decoded.requestId());
        assertEquals(3, decoded.code());
        assertEquals(SERIAL, decoded.serialType());
        assertArrayEquals("oops".getBytes(StandardCharsets.UTF_8), (byte[]) decoded.body());
    }

    @Test
    void internalOnewayRoundTripStaysInternal() {
        RpcRequest decoded = roundTrip(RpcRequest.oneway(-1));

        assertNotNull(decoded);
        assertTrue(decoded.isInternal());
        assertEquals(-1, decoded.getCommand());
    }


    @Test
    void emptyBodyDecodesToEmptyByteArray() {
        RpcResponse decoded = roundTrip(RpcResponse.of(1L, SERIAL, null));

        assertNotNull(decoded);
        assertArrayEquals(new byte[0], (byte[]) decoded.body());
    }

    @Test
    void decoderHandlesPartialAndStickyFrames() {
        ByteBuf first = encode(RpcRequest.oneway(5).routeKey(1L).serialType(SERIAL).body("a"));
        ByteBuf second = encode(RpcRequest.oneway(6).routeKey(2L).serialType(SERIAL).body("bb"));
        ByteBuf combined = Unpooled.buffer();
        combined.writeBytes(first);
        combined.writeBytes(second);
        first.release();
        second.release();

        EmbeddedChannel decodeChannel = new EmbeddedChannel();
        codec().configure(decodeChannel.pipeline());

        decodeChannel.writeInbound(combined.readRetainedSlice(3)); // 半包：长度字段都不完整
        assertNull(decodeChannel.readInbound());

        decodeChannel.writeInbound(combined); // 剩余字节里粘着两帧
        RpcRequest frame1 = decodeChannel.readInbound();
        RpcRequest frame2 = decodeChannel.readInbound();
        assertNotNull(frame1);
        assertNotNull(frame2);
        assertEquals(5, frame1.getCommand());
        assertEquals(6, frame2.getCommand());
        assertArrayEquals("bb".getBytes(StandardCharsets.UTF_8), (byte[]) frame2.getBody());
    }

    @Test
    void errorResponseRoundTripCarriesMessageInMetadata() {
        RpcResponse decoded = roundTrip(RpcResponse.error(7L, 42, "boom"));

        assertNotNull(decoded);
        assertEquals(42, decoded.code());
        assertFalse(decoded.isSuccess());
        assertEquals("boom", decoded.metaString(RpcResponse.META_KEY_ERROR_MESSAGE));
    }

    @Test
    void decoderRejectsUnsupportedVersion() {
        ByteBuf encoded = encode(RpcRequest.oneway(5).routeKey(1L).serialType(SERIAL).body("x"));
        encoded.setByte(4, 99); // length(4) 之后第一个字节是 version

        EmbeddedChannel decodeChannel = new EmbeddedChannel();
        codec().configure(decodeChannel.pipeline());
        assertThrows(DecoderException.class, () -> decodeChannel.writeInbound(encoded));
    }

    @Test
    void decoderRejectsUnknownMetadataType() {
        ByteBuf encoded = encode(RpcResponse.error(1L, 1, "x"));
        // length(4) + version/type/serial/flags(4) + requestId(8) + code(4) + metaCount(1) + key(2)，下一字节是 metadata type
        encoded.setByte(4 + 4 + 8 + 4 + 1 + 2, 9);

        EmbeddedChannel decodeChannel = new EmbeddedChannel();
        codec().configure(decodeChannel.pipeline());
        assertThrows(DecoderException.class, () -> decodeChannel.writeInbound(encoded));
    }

    @Test
    void decoderRejectsMetadataStringLengthBeyondFrame() {
        ByteBuf encoded = encode(RpcResponse.error(1L, 1, "x"));
        // metadata string 长度字段紧跟 metadata type，改成远超帧内剩余字节的值
        encoded.setShort(4 + 4 + 8 + 4 + 1 + 2 + 1, 30000);

        EmbeddedChannel decodeChannel = new EmbeddedChannel();
        codec().configure(decodeChannel.pipeline());
        assertThrows(DecoderException.class, () -> decodeChannel.writeInbound(encoded));
    }

    @Test
    void encoderRejectsOversizedFrame() {
        SerializerManager serializerManager = new SerializerManager();
        serializerManager.register(new StringTestSerializer());
        RpcCodec smallCodec = new RpcCodec(serializerManager, 16);
        EmbeddedChannel channel = new EmbeddedChannel();
        smallCodec.configure(channel.pipeline());

        assertThrows(EncoderException.class, () -> channel.writeOutbound(
                RpcRequest.oneway(5).routeKey(1L).serialType(SERIAL).body("x".repeat(100))));
    }
}
