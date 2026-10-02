package cn.managame.demo.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class GamePacketCodecTest {
    @Test void wireBytesUseBigEndianAndPreserveRawPayload() {
        var channel = new EmbeddedChannel(new GamePacketEncoder());
        try {
            assertTrue(channel.writeOutbound(packet(0x01020304, 0x05060708, 0x090a0b0c, new byte[]{(byte) 0xff})));
            ByteBuf encoded = channel.readOutbound();
            try {
                byte[] actual = new byte[encoded.readableBytes()];
                encoded.readBytes(actual);
                assertArrayEquals(new byte[]{0,0,0,13, 1,2,3,4, 5,6,7,8, 9,10,11,12, (byte) 0xff}, actual);
            } finally { encoded.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void acceptsFragmentedAndCoalescedFramesWithoutRetainingTheInput() {
        var channel = new EmbeddedChannel(new GamePacketDecoder());
        ByteBuf joined = Unpooled.buffer().writeBytes(frame(17, 9, -2, new byte[]{0, (byte) 0xff, (byte) 0x80}))
                .writeBytes(frame(18, 10, 0, new byte[0]));
        try {
            for (int i = 0; i < 15; i++) {
                assertFalse(channel.writeInbound(joined.readRetainedSlice(1)));
                assertNull(channel.readInbound());
            }
            assertTrue(channel.writeInbound(joined.readRetainedSlice(joined.readableBytes())));
            GamePacket first = channel.readInbound();
            GamePacket second = channel.readInbound();
            assertEquals(17, first.getCommand());
            assertEquals(9, first.getSeq());
            assertEquals(-2, first.getCode());
            assertArrayEquals(new byte[]{0, (byte) 0xff, (byte) 0x80}, first.getBody());
            assertEquals(18, second.getCommand());
            assertEquals(10, second.getSeq());
            assertEquals(0, second.getCode());
            assertArrayEquals(new byte[0], second.getBody());
            assertNull(channel.readInbound());
            assertEquals(1, joined.refCnt());
        } finally { joined.release(); channel.finishAndReleaseAll(); }
    }

    @Test void frameLimitIncludesTheLengthFieldAndAllowsTheExactBoundary() {
        var channel = new EmbeddedChannel(new GamePacketDecoder(18), new GamePacketEncoder(18));
        try {
            assertTrue(channel.writeOutbound(packet(1, 2, 3, new byte[]{4,5})));
            ByteBuf encoded = channel.readOutbound();
            assertTrue(channel.writeInbound(encoded));
            GamePacket result = channel.readInbound();
            assertArrayEquals(new byte[]{4,5}, result.getBody());
            EncoderException failure = assertThrows(EncoderException.class,
                    () -> channel.writeOutbound(packet(1,2,3,new byte[]{4,5,6})));
            assertInstanceOf(TooLongFrameException.class, failure.getCause());
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void rejectsInvalidAndOversizedLengthBeforeWaitingForPayload() {
        assertRejectedLength(11, CorruptedFrameException.class);
        assertRejectedLength(29, TooLongFrameException.class);
        assertRejectedLength(-1, TooLongFrameException.class);
    }

    @Test void truncatedFrameAtEofIsRejected() {
        byte[] complete = frame(1, 2, 3, new byte[]{4,5,6});
        for (int prefix : new int[]{1,3,4,15,16,18}) {
            var channel = new EmbeddedChannel(new GamePacketDecoder());
            ByteBuf partial = Unpooled.wrappedBuffer(Arrays.copyOf(complete, prefix));
            try {
                assertFalse(channel.writeInbound(partial));
                assertThrows(CorruptedFrameException.class, channel::finish);
                assertEquals(0, partial.refCnt());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test void validatesConfigurationAndRequiresANonNullBody() {
        assertThrows(IllegalArgumentException.class, () -> new GamePacketDecoder(15));
        assertThrows(IllegalArgumentException.class, () -> new GamePacketEncoder(15));
        assertThrows(NullPointerException.class, () -> new GamePacket().setBody(null));
        assertArrayEquals(new byte[0], new GamePacket().getBody());
    }

    private static void assertRejectedLength(int length, Class<? extends Throwable> exception) {
        var channel = new EmbeddedChannel(new GamePacketDecoder(32));
        ByteBuf input = Unpooled.buffer().writeInt(length);
        try { assertThrows(exception, () -> channel.writeInbound(input)); }
        finally { channel.pipeline().remove(GamePacketDecoder.class); channel.finishAndReleaseAll(); }
        assertEquals(0, input.refCnt());
    }

    private static byte[] frame(int command, int seq, int code, byte[] body) {
        ByteBuf buffer = Unpooled.buffer().writeInt(12 + body.length)
                .writeInt(command).writeInt(seq).writeInt(code).writeBytes(body);
        try {
            byte[] result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally { buffer.release(); }
    }

    static GamePacket packet(int command, int seq, int code, byte[] body) {
        var result = new GamePacket();
        result.setCommand(command); result.setSeq(seq); result.setCode(code); result.setBody(body);
        return result;
    }
}
