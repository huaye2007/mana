package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GatewayPacketCodecTest {
    @Test void makesCopyAndOwnershipTransferFactoriesExplicit() {
        byte[] copiedBody = {1, 2};
        GatewayPacket copied = GatewayPacket.of(1, 1, 0, copiedBody);
        copiedBody[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, copied.getBody());

        byte[] ownedBody = {3, 4};
        GatewayPacket wrapped = GatewayPacket.wrap(1, 1, 0, ownedBody);
        assertSame(ownedBody, wrapped.getBody());
    }

    @Test void roundTripsFragmentedAndCoalescedFrames() {
        byte[] first = encode(GatewayPacket.of(1000, 7, 0, "hello".getBytes(StandardCharsets.UTF_8)));
        byte[] second = encode(GatewayPacket.of(1001, 8, 3, GatewayPacketConstant.EMPTY_BODY));
        EmbeddedChannel channel = new EmbeddedChannel(new GatewayPacketDecoder());
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(first, 0, GatewayPacketConstant.HEAD_LENGTH)));
        ByteBuf tail = Unpooled.buffer(first.length + second.length);
        tail.writeBytes(first, GatewayPacketConstant.HEAD_LENGTH, first.length - GatewayPacketConstant.HEAD_LENGTH);
        tail.writeBytes(second);
        assertTrue(channel.writeInbound(tail));
        GatewayPacket a = channel.readInbound();
        GatewayPacket b = channel.readInbound();
        assertEquals(1000, a.getCommand());
        assertEquals(7, a.getSeq());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), a.getBody());
        assertEquals(1001, b.getCommand());
        assertEquals(3, b.getCode());
        assertEquals(0, b.getBody().length);
        channel.finishAndReleaseAll();
    }

    @Test void rejectsOversizedAndNegativeBodies() {
        for (int length : new int[]{-1, GatewayPacketConstant.MAX_BODY_LENGTH + 1}) {
            ByteBuf bad = Unpooled.buffer(GatewayPacketConstant.HEAD_LENGTH).writeInt(length).writeZero(13);
            EmbeddedChannel channel = new EmbeddedChannel(new GatewayPacketDecoder());
            channel.writeInbound(bad);
            assertFalse(channel.isOpen());
            channel.finishAndReleaseAll();
        }
    }

    @Test void appliesBodyCodecWithoutMutatingPacket() {
        BodyCodec xor = new BodyCodec() {
            @Override public byte[] decode(byte flags, byte[] body) { return transform(body); }
            @Override public byte[] encode(byte flags, byte[] body) { return transform(body); }
            private byte[] transform(byte[] input) {
                byte[] result = input.clone();
                for (int i = 0; i < result.length; i++) result[i] ^= 0x55;
                return result;
            }
        };
        GatewayPacket input = GatewayPacket.of(9, 1, 0, new byte[]{1, 2, 3});
        EmbeddedChannel encoder = new EmbeddedChannel(new GatewayPacketEncoder(xor));
        encoder.writeOutbound(input);
        ByteBuf wire = encoder.readOutbound();
        EmbeddedChannel decoder = new EmbeddedChannel(new GatewayPacketDecoder(xor));
        decoder.writeInbound(wire);
        GatewayPacket output = decoder.readInbound();
        assertArrayEquals(new byte[]{1, 2, 3}, output.getBody());
        assertArrayEquals(new byte[]{1, 2, 3}, input.getBody());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static byte[] encode(GatewayPacket packet) {
        EmbeddedChannel channel = new EmbeddedChannel(new GatewayPacketEncoder());
        channel.writeOutbound(packet);
        ByteBuf buffer = channel.readOutbound();
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes).release();
        channel.finishAndReleaseAll();
        return bytes;
    }
}
