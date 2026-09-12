package cn.managame.rpc.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.*;

import java.nio.ByteOrder;
import java.util.List;

/** One TCP framing contract for both directions: signed length, bounds and a four-byte prefix. */
final class NettyRpcFrameCodec
        extends CombinedChannelDuplexHandler<LengthFieldBasedFrameDecoder, LengthFieldPrepender> {
    private static final int PREFIX_BYTES = Integer.BYTES;

    NettyRpcFrameCodec(int maxFrameBytes) {
        super(decoder(maxFrameBytes), encoder(maxFrameBytes));
    }

    static boolean accepts(int frameBytes, int maxFrameBytes) {
        return frameBytes > 0 && frameBytes <= maxFrameBytes;
    }

    private static void checkLength(int frameBytes, int maximum) {
        if (frameBytes <= 0) throw new CorruptedFrameException("RPC frameLength must be positive");
        if (frameBytes > maximum) throw new TooLongFrameException("RPC frameLength exceeds limit");
    }

    private static LengthFieldBasedFrameDecoder decoder(int maximum) {
        return new LengthFieldBasedFrameDecoder(
                Math.addExact(maximum, PREFIX_BYTES), 0, PREFIX_BYTES, 0, PREFIX_BYTES) {
            @Override
            protected long getUnadjustedFrameLength(
                    ByteBuf input, int offset, int length, ByteOrder order) {
                int size = input.getInt(offset);
                checkLength(size, maximum);
                return size;
            }
        };
    }

    private static LengthFieldPrepender encoder(int maximum) {
        return new LengthFieldPrepender(PREFIX_BYTES) {
            @Override
            protected void encode(ChannelHandlerContext context, ByteBuf frame, List<Object> output)
                    throws Exception {
                checkLength(frame.readableBytes(), maximum);
                // Netty writes a prefix and retains the existing frame; no full-frame copy.
                super.encode(context, frame, output);
            }
        };
    }
}
