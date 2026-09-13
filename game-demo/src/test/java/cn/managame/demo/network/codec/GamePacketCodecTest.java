package cn.managame.demo.network.codec;

import cn.managame.demo.protocol.GamePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GamePacketCodecTest {
    @Test void encoderMatchesFixedWireLayoutAndOwnsBodyBytes() {
        var channel = new EmbeddedChannel(new GamePacketEncoder());
        byte[] body = { (byte) 0xaa, (byte) 0xbb };
        var packet = new GamePacket(1001, 42, 1002, GamePacket.RESPONSE, body);
        body[0] = 0;
        packet.body()[0] = 0;
        try {
            assertTrue(channel.writeOutbound(packet));
            ByteBuf encoded = channel.readOutbound();
            try {
                assertEquals("00000012000003e90000002a000003ea00000001aabb", ByteBufUtil.hexDump(encoded));
            } finally { encoded.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void decoderAcceptsFragmentsAndCoalescedFramesWithoutBorrowedBody() {
        var channel = new EmbeddedChannel(new GamePacketDecoder());
        byte[] frame = ByteBufUtil.decodeHexDump("00000012000003e90000002a00000000000000000102");
        try {
            for (int i = 0; i < frame.length - 1; i++) {
                assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { frame[i] })));
            }
            var combined = Unpooled.buffer(1 + frame.length).writeByte(frame[frame.length - 1]).writeBytes(frame);
            assertTrue(channel.writeInbound(combined));
            GamePacket first = channel.readInbound(), second = channel.readInbound();
            assertEquals(1001, first.command()); assertEquals(42, first.requestId());
            assertEquals(0, first.code()); assertEquals(GamePacket.REQUEST, first.flags());
            assertArrayEquals(new byte[] { 1, 2 }, first.body());
            assertArrayEquals(first.body(), second.body());
            assertNull(channel.readInbound());
            assertEquals(0, combined.refCnt());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedHeadersAndOversizedLengthsAreRejected() {
        String[] invalid = {
            "00000003aabbcc", // Header too short.
            "0000001000000000000000010000000000000000", // command zero.
            "0000001000000001000000000000000000000000", // requestId zero.
            "000000100000000100000001ffffffff00000001", // negative code.
            "0000001000000001000000010000000100000000", // request with error code.
            "0000001000000001000000010000000000000003", // unknown flags.
            "0000001000000001000000010000000000000002", // notification with a request requestId.
            "0000001000000001000000000000000100000002", // notification with an error code.
            "0000001000000001ffffffff0000000000000002", // notification with a negative requestId.
            "0000001000000001000000000000000000000001", // response with requestId zero.
            "00001000", // length + prefix exceeds the 4096 byte limit.
            "ffffffff"
        };
        for (String hex : invalid) {
            var channel = new EmbeddedChannel(new GamePacketDecoder());
            try {
                assertThrows(DecoderException.class, () -> channel.writeInbound(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex))), hex);
                assertNull(channel.readInbound());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test void notificationHasIndependentCommandAndZeroCorrelation() {
        var encoder = new EmbeddedChannel(new GamePacketEncoder());
        var decoder = new EmbeddedChannel(new GamePacketDecoder());
        try {
            encoder.writeOutbound(new GamePacket(2001, 0, 0, GamePacket.NOTIFY, new byte[] { 1, 2 }));
            ByteBuf bytes = encoder.readOutbound();
            assertEquals("00000012000007d10000000000000000000000020102", ByteBufUtil.hexDump(bytes));
            decoder.writeInbound(bytes);
            GamePacket decoded = decoder.readInbound();
            assertTrue(decoded.isNotify());
            assertFalse(decoded.isResponse());
            assertEquals(2001, decoded.command());
            assertEquals(0, decoded.requestId());
            assertEquals(0, decoded.code());
            assertArrayEquals(new byte[] { 1, 2 }, decoded.body());
        } finally { encoder.finishAndReleaseAll(); decoder.finishAndReleaseAll(); }
    }

    @Test void maximumBodyFitsExactlyAndEmptyBodiesAreValidEnvelopes() {
        for (int size : new int[] { 0, GamePacket.MAX_BODY_BYTES }) {
            var encoder = new EmbeddedChannel(new GamePacketEncoder());
            var decoder = new EmbeddedChannel(new GamePacketDecoder());
            try {
                encoder.writeOutbound(new GamePacket(1, 1, 0, GamePacket.RESPONSE, new byte[size]));
                ByteBuf bytes = encoder.readOutbound();
                assertEquals(20 + size, bytes.readableBytes());
                decoder.writeInbound(bytes);
                GamePacket decoded = decoder.readInbound();
                assertEquals(size, decoded.body().length);
            } finally { encoder.finishAndReleaseAll(); decoder.finishAndReleaseAll(); }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new GamePacket(1, 1, 0, GamePacket.REQUEST, new byte[GamePacket.MAX_BODY_BYTES + 1]));
    }
}
