package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.Objects;

@ChannelHandler.Sharable
public final class GatewayPacketEncoder extends MessageToByteEncoder<GatewayPacket> {
    private final BodyCodec bodyCodec;
    private final int maxBodyLength;

    public GatewayPacketEncoder() { this(BodyCodec.IDENTITY); }
    public GatewayPacketEncoder(BodyCodec bodyCodec) { this(bodyCodec, GatewayPacketConstant.MAX_BODY_LENGTH); }
    public GatewayPacketEncoder(BodyCodec bodyCodec, int maxBodyLength) {
        this.bodyCodec = Objects.requireNonNull(bodyCodec, "bodyCodec");
        if (maxBodyLength < 0) throw new IllegalArgumentException("maxBodyLength must be non-negative");
        this.maxBodyLength = maxBodyLength;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, GatewayPacket packet, ByteBuf out) {
        byte[] body = Objects.requireNonNull(bodyCodec.encode(packet.getFlags(), packet.getBody()), "encoded body");
        if (body.length > maxBodyLength) {
            throw new IllegalArgumentException("gateway packet body exceeds " + maxBodyLength + " bytes");
        }
        out.writeInt(body.length);
        out.writeInt(packet.getCommand());
        out.writeInt(packet.getSeq());
        out.writeInt(packet.getCode());
        out.writeByte(packet.getFlags());
        out.writeBytes(body);
    }
}
