package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 外网帧编码器，帧格式与 {@link GatewayPacketDecoder} 一致。
 * body 为 null 时按空 body 编码；出站按 flags 走 {@link BodyCodec}（压缩/加密）。
 */
public class GatewayPacketEncoder extends MessageToByteEncoder<GatewayPacket> {

    private final BodyCodec bodyCodec;

    public GatewayPacketEncoder() {
        this(BodyCodec.IDENTITY);
    }

    public GatewayPacketEncoder(BodyCodec bodyCodec) {
        this.bodyCodec = bodyCodec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, GatewayPacket msg, ByteBuf out) {
        byte[] body = msg.getBody() == null ? GatewayPacketConstant.EMPTY_BODY : msg.getBody();
        byte[] wireBody = bodyCodec.encode(msg.getFlags(), body);
        out.writeInt(wireBody.length);
        out.writeInt(msg.getCommand());
        out.writeInt(msg.getSeq());
        out.writeInt(msg.getCode());
        out.writeByte(msg.getFlags());
        out.writeBytes(wireBody);
    }
}
