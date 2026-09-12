package cn.managame.rpc.netty;

import static cn.managame.rpc.netty.RpcTcpTest.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.rpc.*;

import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RpcFrameCodecTest {
    @Test
    void allMessageKindsShareFramingAcrossPartialAndCoalescedReads() {
        var channel = new EmbeddedChannel(new NettyRpcFrameCodec(128));
        var codec = new DefaultRpcCodec();
        ByteBuf inner = Unpooled.wrappedBuffer(new byte[] {99, 1, 2});
        ByteBuf wire = Unpooled.buffer();
        var expected = new ArrayList<byte[]>();
        try {
            for (RpcMessage message :
                    List.of(
                            new RpcHandshake(RpcHandshake.Kind.HELLO, 10, 20, 0, 1),
                            new RpcHeartbeat(RpcHeartbeat.Kind.PING, 1),
                            new RpcHeartbeat(RpcHeartbeat.Kind.PONG, 1),
                            new RpcRequest(1, 1, RpcOptions.DEFAULT, raw(new byte[] {1, 2})),
                            new RpcResponse(1, 0, RpcMetadata.EMPTY, raw(new byte[] {3})),
                            new RpcRouteMessage(10, 20, inner))) {
                ByteBuf frame = codec.encode(message);
                expected.add(ByteBufUtil.getBytes(frame));
                assertTrue(channel.writeOutbound(frame));
                ByteBuf prefix = channel.readOutbound();
                ByteBuf payload = channel.readOutbound();
                try {
                    assertEquals(expected.getLast().length, prefix.getInt(prefix.readerIndex()));
                    assertEquals(
                            4L + payload.readableBytes(),
                            prefix.readableBytes() + payload.readableBytes());
                    assertSame(
                            frame, payload, "Framing must retain the payload without copying it");
                    wire.writeBytes(prefix).writeBytes(payload);
                } finally {
                    prefix.release();
                    payload.release();
                }
                assertNull(channel.readOutbound());
            }
            // Partial prefix, then a partial first frame, followed by several complete frames.
            assertFalse(channel.writeInbound(wire.readRetainedSlice(3)));
            assertFalse(channel.writeInbound(wire.readRetainedSlice(2)));
            assertTrue(channel.writeInbound(wire.readRetainedSlice(wire.readableBytes())));
            for (byte[] bytes : expected) {
                ByteBuf frame = channel.readInbound();
                try {
                    assertArrayEquals(bytes, ByteBufUtil.getBytes(frame));
                } finally {
                    frame.release();
                }
            }
            assertNull(channel.readInbound());
            assertEquals(1, inner.refCnt());
        } finally {
            channel.finishAndReleaseAll();
            wire.release();
            inner.release();
        }
    }

    @Test
    void invalidOutboundFramesFailAndReleaseTheirReference() {
        for (int size : new int[] {0, 129}) {
            var channel = new EmbeddedChannel(new NettyRpcFrameCodec(128));
            ByteBuf frame = Unpooled.buffer(Math.max(1, size)).writeZero(size);
            try {
                assertThrows(EncoderException.class, () -> channel.writeOutbound(frame));
                assertEquals(0, frame.refCnt());
                assertNull(channel.readOutbound(), "Invalid frames must not write a prefix");
            } finally {
                channel.finishAndReleaseAll();
                if (frame.refCnt() > 0) frame.release();
            }
        }
    }
}
