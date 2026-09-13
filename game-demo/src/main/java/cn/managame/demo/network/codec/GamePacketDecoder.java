package cn.managame.demo.network.codec;

import cn.managame.demo.protocol.GamePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/** TCP framing plus command/requestId/code/flags decoding; no knowledge of business body formats. */
public final class GamePacketDecoder extends LengthFieldBasedFrameDecoder {
    public GamePacketDecoder() { super(GamePacket.MAX_FRAME_BYTES, 0, Integer.BYTES, 0, Integer.BYTES); }

    @Override protected Object decode(ChannelHandlerContext context, ByteBuf input) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(context, input);
        if (frame == null) return null;
        try {
            if (frame.readableBytes() < GamePacket.HEADER_BYTES) throw new CorruptedFrameException("Incomplete packet header");
            int command = frame.readInt();
            int requestId = frame.readInt();
            int code = frame.readInt();
            int flags = frame.readInt();
            byte[] body = new byte[frame.readableBytes()];
            frame.readBytes(body);
            try { return new GamePacket(command, requestId, code, flags, body); }
            catch (IllegalArgumentException failure) { throw new CorruptedFrameException("Invalid packet header", failure); }
        } finally { frame.release(); }
    }
}
