package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.Objects;

public final class GatewayPacketDecoder extends ByteToMessageDecoder {
    private final BodyCodec bodyCodec;
    private final int maxBodyLength;

    public GatewayPacketDecoder() { this(BodyCodec.IDENTITY); }
    public GatewayPacketDecoder(BodyCodec bodyCodec) { this(bodyCodec, GatewayPacketConstant.MAX_BODY_LENGTH); }
    public GatewayPacketDecoder(BodyCodec bodyCodec, int maxBodyLength) {
        this.bodyCodec = Objects.requireNonNull(bodyCodec, "bodyCodec");
        if (maxBodyLength < 0) throw new IllegalArgumentException("maxBodyLength must be non-negative");
        this.maxBodyLength = maxBodyLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < GatewayPacketConstant.HEAD_LENGTH) return;
        in.markReaderIndex();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > maxBodyLength) {
            in.skipBytes(in.readableBytes());
            ctx.close();
            return;
        }
        if (in.readableBytes() < GatewayPacketConstant.HEAD_LENGTH - Integer.BYTES + bodyLength) {
            in.resetReaderIndex();
            return;
        }
        int command = in.readInt();
        int seq = in.readInt();
        int code = in.readInt();
        byte flags = in.readByte();
        byte[] wireBody = new byte[bodyLength];
        in.readBytes(wireBody);
        byte[] body;
        try {
            body = Objects.requireNonNull(bodyCodec.decode(flags, wireBody), "decoded body");
        } catch (RuntimeException error) {
            ctx.close();
            return;
        }
        if (body.length > maxBodyLength) {
            ctx.close();
            return;
        }
        GatewayPacket packet = GatewayPacket.wrap(command, seq, code, body);
        packet.setFlags(flags);
        out.add(packet);
    }
}
