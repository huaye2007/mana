package cn.managame.dev.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 外网帧编码器。帧格式与 {@link GamePacketDecoder} 一致。
 *
 * <p>出站 body 必须是已序列化的 {@code byte[]}（见 {@link GamePacket} 契约），
 * 其它类型是编程错误，直接抛异常让 pipeline 的 exceptionCaught 处理，
 * 而不是静默丢 body 发出一帧空包。</p>
 */
public class GamePacketEncoder extends MessageToByteEncoder<GamePacket> {

    @Override
    protected void encode(ChannelHandlerContext ctx, GamePacket msg, ByteBuf out) {
        Object body = msg.getBody();
        if (body != null && !(body instanceof byte[])) {
            throw new IllegalArgumentException("出站 GamePacket.body 必须是已序列化的 byte[]，实际是 "
                    + body.getClass().getName() + ", command=" + msg.getCommand());
        }
        byte[] bodyBytes = body == null ? GamePacketConstant.EMPTY_BODY : (byte[]) body;
        out.writeInt(bodyBytes.length);
        out.writeInt(msg.getCommand());
        out.writeInt(msg.getSeq());
        out.writeInt(msg.getCode());
        out.writeByte(msg.getFlags());
        out.writeBytes(bodyBytes);
    }
}
