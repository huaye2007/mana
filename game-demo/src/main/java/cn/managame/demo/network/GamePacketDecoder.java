package cn.managame.demo.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;

import java.util.List;

public class GamePacketDecoder extends ByteToMessageDecoder {
    private final int maxFrameLength;

    public GamePacketDecoder() {
        this(GamePacket.DEFAULT_MAX_FRAME_LENGTH);
    }

    public GamePacketDecoder(int maxFrameLength) {
        if (maxFrameLength < GamePacket.HEADER_BYTES) {
            throw new IllegalArgumentException("maxFrameLength must include the 16-byte header");
        }
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (input.readableBytes() < Integer.BYTES) return;
        // Length excludes its own four bytes and includes command, seq, and code.
        long length = input.getUnsignedInt(input.readerIndex());
        if (length < GamePacket.HEADER_BYTES - Integer.BYTES) {
            throw new CorruptedFrameException("GamePacket length must include command, seq, and code");
        }
        if (length > maxFrameLength - Integer.BYTES) {
            throw new TooLongFrameException("GamePacket exceeds maxFrameLength=" + maxFrameLength);
        }
        if (input.readableBytes() < length + Integer.BYTES) return;

        input.skipBytes(Integer.BYTES);
        var packet = new GamePacket();
        packet.setCommand(input.readInt());
        packet.setSeq(input.readInt());
        packet.setCode(input.readInt());
        byte[] body = new byte[(int) length - (GamePacket.HEADER_BYTES - Integer.BYTES)];
        input.readBytes(body);
        packet.setBody(body);
        output.add(packet);
    }

    @Override
    protected void decodeLast(ChannelHandlerContext context, ByteBuf input, List<Object> output) throws Exception {
        super.decodeLast(context, input, output);
        if (input.isReadable()) {
            throw new CorruptedFrameException("Connection closed with an incomplete GamePacket");
        }
    }
}
