package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关帧编解码往返：body 作为原始字节透传（网关不反序列化），头字段精确还原，
 * 半包等待、粘包连续解析、非法 bodyLength 断连。全程 EmbeddedChannel，无 socket。
 */
class GatewayPacketCodecTest {

    private static byte[] encode(GatewayPacket packet) {
        EmbeddedChannel encoder = new EmbeddedChannel(new GatewayPacketEncoder());
        assertTrue(encoder.writeOutbound(packet));
        ByteBuf out = encoder.readOutbound();
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        encoder.finish();
        return bytes;
    }

    @Test
    void roundTripsHeaderAndRawBody() {
        byte[] body = "hello-body".getBytes(StandardCharsets.UTF_8);
        GatewayPacket packet = GatewayPacket.of(1000, 7, 0, body);

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder());
        assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(encode(packet))));
        GatewayPacket decoded = decoder.readInbound();
        decoder.finish();

        assertEquals(1000, decoded.getCommand());
        assertEquals(7, decoded.getSeq());
        assertEquals(0, decoded.getCode());
        assertArrayEquals(body, decoded.getBody());
    }

    @Test
    void emptyBodyRoundTrips() {
        byte[] frame = encode(GatewayPacket.of(1, 0, 6, GatewayPacketConstant.EMPTY_BODY));
        assertEquals(GatewayPacketConstant.HEAD_LENGTH, frame.length);

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder());
        decoder.writeInbound(Unpooled.wrappedBuffer(frame));
        GatewayPacket decoded = decoder.readInbound();
        decoder.finish();

        assertEquals(1, decoded.getCommand());
        assertEquals(6, decoded.getCode());
        assertEquals(0, decoded.getBody().length);
    }

    @Test
    void splitFrameDecodesOnlyWhenComplete() {
        byte[] frame = encode(GatewayPacket.of(1000, 1, 0, "abc".getBytes(StandardCharsets.UTF_8)));
        int split = GatewayPacketConstant.HEAD_LENGTH; // 头到了、body 还没到

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder());
        assertFalse(decoder.writeInbound(Unpooled.wrappedBuffer(frame, 0, split)));
        assertNull(decoder.readInbound(), "帧不完整时不应解出包");

        decoder.writeInbound(Unpooled.wrappedBuffer(frame, split, frame.length - split));
        GatewayPacket decoded = decoder.readInbound();
        decoder.finish();

        assertNotNull(decoded);
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), decoded.getBody());
    }

    @Test
    void backToBackFramesBothDecode() {
        byte[] a = encode(GatewayPacket.of(1000, 1, 0, "aa".getBytes(StandardCharsets.UTF_8)));
        byte[] b = encode(GatewayPacket.of(1001, 2, 0, "bbbb".getBytes(StandardCharsets.UTF_8)));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder());
        decoder.writeInbound(Unpooled.wrappedBuffer(both));
        GatewayPacket first = decoder.readInbound();
        GatewayPacket second = decoder.readInbound();
        decoder.finish();

        assertEquals(1000, first.getCommand());
        assertEquals(1001, second.getCommand());
        assertArrayEquals("bbbb".getBytes(StandardCharsets.UTF_8), second.getBody());
    }

    @Test
    void illegalBodyLengthClosesConnection() {
        ByteBuf bad = Unpooled.buffer();
        bad.writeInt(GatewayPacketConstant.MAX_BODY_LENGTH + 1); // 超上限
        bad.writeInt(1000);
        bad.writeInt(1);
        bad.writeInt(0);
        bad.writeByte(0);

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder());
        decoder.writeInbound(bad);

        assertFalse(decoder.isOpen(), "非法 bodyLength 应关闭连接");
        assertNull(decoder.readInbound());
    }

    @Test
    void bodyCodecTransformsRoundTrip() {
        // 对称变换：出站每字节 +1（XOR 演示），入站 -1 还原
        BodyCodec shift = new BodyCodec() {
            @Override
            public byte[] decode(byte flags, byte[] body) {
                return map(body, (byte) -1);
            }

            @Override
            public byte[] encode(byte flags, byte[] body) {
                return map(body, (byte) 1);
            }

            private byte[] map(byte[] in, byte delta) {
                byte[] out = new byte[in.length];
                for (int i = 0; i < in.length; i++) {
                    out[i] = (byte) (in[i] + delta);
                }
                return out;
            }
        };
        byte[] body = "secret".getBytes(StandardCharsets.UTF_8);

        EmbeddedChannel encoder = new EmbeddedChannel(new GatewayPacketEncoder(shift));
        encoder.writeOutbound(GatewayPacket.of(1000, 1, 0, body));
        ByteBuf wire = encoder.readOutbound();
        byte[] wireBytes = new byte[wire.readableBytes()];
        wire.readBytes(wireBytes);
        wire.release();
        encoder.finish();

        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder(shift));
        decoder.writeInbound(Unpooled.wrappedBuffer(wireBytes));
        GatewayPacket decoded = decoder.readInbound();
        decoder.finish();

        assertArrayEquals(body, decoded.getBody(), "出站编码 + 入站解码应还原原始 body");
    }
}
