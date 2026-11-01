package cn.managame.example;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import java.io.IOException;
import java.util.List;

/**
 * The game's own codec, after network's length-field framing: a whole frame in, a {@link Packet} out, and back. Protobuf
 * bodies are read from the frame's buffer and written into the outgoing one, with no byte array between. A frame it
 * cannot read, such as an unknown message id, closes the connection through the gateway's onException. Stateless, so
 * one instance serves every connection.
 */
@Sharable
final class PacketCodec extends MessageToMessageCodec<ByteBuf, Packet> {
    static final PacketCodec INSTANCE = new PacketCodec();
    private static final int HEADER = 12;

    private PacketCodec() {}

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        int id = frame.readInt(), seq = frame.readInt(), code = frame.readInt();
        out.add(new Packet(seq, code, id == 0 ? null : Protocol.parse(id, frame)));
    }

    @Override protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) throws IOException {
        Message message = packet.message();
        int size = message == null ? 0 : message.getSerializedSize();
        ByteBuf frame = ctx.alloc().buffer(HEADER + size)
                .writeInt(message == null ? 0 : Protocol.idOf(message)).writeInt(packet.seq()).writeInt(packet.code());
        if (message != null) message.writeTo(new ByteBufOutputStream(frame));
        out.add(frame);
    }
}
