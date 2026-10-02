package cn.managame.demo.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.TooLongFrameException;

@ChannelHandler.Sharable
public class GamePacketEncoder extends MessageToByteEncoder<GamePacket> {
    private final int maxFrameLength;

    public GamePacketEncoder() {
        this(GamePacket.DEFAULT_MAX_FRAME_LENGTH);
    }

    public GamePacketEncoder(int maxFrameLength) {
        if (maxFrameLength < GamePacket.HEADER_BYTES) {
            throw new IllegalArgumentException("maxFrameLength must include the 16-byte header");
        }
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void encode(ChannelHandlerContext context, GamePacket packet, ByteBuf output) {
        byte[] body = packet.getBody();
        if (body.length > maxFrameLength - GamePacket.HEADER_BYTES) {
            throw new TooLongFrameException("GamePacket exceeds maxFrameLength=" + maxFrameLength);
        }
        output.ensureWritable(GamePacket.HEADER_BYTES + body.length);
        output.writeInt(GamePacket.HEADER_BYTES - Integer.BYTES + body.length);
        output.writeInt(packet.getCommand());
        output.writeInt(packet.getSeq());
        output.writeInt(packet.getCode());
        output.writeBytes(body);
    }
}
