package cn.managame.demo.network.codec;

import cn.managame.demo.protocol.GamePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Big-endian: length (excluding itself), command, requestId, code, flags, then opaque body. */
public final class GamePacketEncoder extends MessageToByteEncoder<GamePacket> {
    @Override protected void encode(ChannelHandlerContext context, GamePacket packet, ByteBuf output) {
        byte[] body = packet.body();
        output.writeInt(GamePacket.HEADER_BYTES + body.length)
                .writeInt(packet.command()).writeInt(packet.requestId()).writeInt(packet.code()).writeInt(packet.flags())
                .writeBytes(body);
    }
}
