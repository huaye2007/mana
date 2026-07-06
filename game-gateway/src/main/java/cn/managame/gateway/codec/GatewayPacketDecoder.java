package cn.managame.gateway.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 外网帧解码器。帧格式与 game-dev 完全一致：
 * {@code bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)}。
 *
 * <p>与游戏服的区别：网关不做 body 反序列化，读出的 body 只按 flags 走
 * {@link BodyCodec}（解密/解压）后原样携带；command 是否合法交给后端判断。
 * bodyLength 非法直接断连（协议已错位，无法恢复）；{@link BodyCodec} 抛错按
 * 该帧非法处理，回 {@link GatewayErrorCode#BAD_REQUEST} 并丢弃该帧（流仍对齐）。</p>
 *
 * <p>TCP 与 WebSocket 共用：WS 管线的 binary 帧解出的 ByteBuf 会被本 decoder
 * 累积，跨 WS 帧的半包/粘包同样能正确重组。</p>
 */
public class GatewayPacketDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(GatewayPacketDecoder.class);

    private final BodyCodec bodyCodec;

    public GatewayPacketDecoder() {
        this(BodyCodec.IDENTITY);
    }

    public GatewayPacketDecoder(BodyCodec bodyCodec) {
        this.bodyCodec = bodyCodec;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int readableBytes = in.readableBytes();
        if (readableBytes < GatewayPacketConstant.HEAD_LENGTH) {
            return;
        }
        in.markReaderIndex();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > GatewayPacketConstant.MAX_BODY_LENGTH) {
            logger.error("illegal bodyLength={}, closing connection, remote={}",
                    bodyLength, ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        if (readableBytes - GatewayPacketConstant.HEAD_LENGTH < bodyLength) {
            in.resetReaderIndex();
            return;
        }

        int command = in.readInt();
        int seq = in.readInt();
        int code = in.readInt();
        byte flags = in.readByte();
        byte[] bodyBytes = bodyLength > 0 ? new byte[bodyLength] : GatewayPacketConstant.EMPTY_BODY;
        if (bodyLength > 0) {
            in.readBytes(bodyBytes);
        }

        byte[] body;
        try {
            body = bodyCodec.decode(flags, bodyBytes);
        } catch (RuntimeException e) {
            logger.warn("body decode failed, command={}, flags={}, packet dropped", command, flags, e);
            ctx.writeAndFlush(GatewayPacket.of(command, seq,
                    GatewayErrorCode.BAD_REQUEST, GatewayPacketConstant.EMPTY_BODY));
            return;
        }

        GatewayPacket packet = new GatewayPacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        packet.setFlags(flags);
        packet.setBody(body);
        out.add(packet);
    }
}
