package com.github.huaye2007.mana.dev.protocol;

import com.github.huaye2007.mana.runtime.command.CommandMeta;
import com.github.huaye2007.mana.runtime.command.CommandRegistry;
import com.github.huaye2007.mana.serialization.ISerializer;
import com.github.huaye2007.mana.serialization.SerializerManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 外网帧解码器。
 *
 * <p>帧格式：{@code bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)}，
 * 帧头固定 {@link GamePacketConstant#HEAD_LENGTH} 字节。</p>
 *
 * <p>解出帧头后读取 body 原始字节，按固定序列化方式（{@link GamePacketConstant#BODY_SERIAL_TYPE}）
 * 反序列化成 command 对应的业务参数对象。command 未注册或反序列化失败时丢弃该包
 * （body 字节已读出，流保持对齐），并按原 command/seq 直接回一帧错误码，客户端不至于干等。</p>
 */
public class GamePacketDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(GamePacketDecoder.class);

    private static final ISerializer SERIALIZER =
            SerializerManager.getInstance().getISerializer(GamePacketConstant.BODY_SERIAL_TYPE);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int readableBytes = in.readableBytes();
        if (readableBytes < GamePacketConstant.HEAD_LENGTH) {
            return;
        }
        in.markReaderIndex();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > GamePacketConstant.MAX_BODY_LENGTH) {
            logger.error("illegal bodyLength={}, closing connection", bodyLength);
            ctx.close();
            return;
        }
        if (readableBytes - GamePacketConstant.HEAD_LENGTH < bodyLength) {
            in.resetReaderIndex();
            return;
        }

        int command = in.readInt();
        int seq = in.readInt();
        int code = in.readInt();
        byte flags = in.readByte();
        byte[] bodyBytes = new byte[bodyLength];
        in.readBytes(bodyBytes);
        CommandMeta meta = CommandRegistry.getInstance().getCommandMeta(command);
        if (meta == null) {
            logger.warn("unknown command={}, packet dropped", command);
            ctx.writeAndFlush(GamePacket.of(command, seq,
                    GameErrorCode.UNKNOWN_COMMAND, GamePacketConstant.EMPTY_BODY));
            return;
        }

        Object body;
        try {
            body = bodyLength > 0 ? SERIALIZER.deserialize(bodyBytes, meta.getParamTypes()[1]) : null;
        } catch (RuntimeException e) {
            logger.warn("deserialize body failed, command={}, packet dropped", command, e);
            ctx.writeAndFlush(GamePacket.of(command, seq,
                    GameErrorCode.BAD_REQUEST, GamePacketConstant.EMPTY_BODY));
            return;
        }

        GamePacket gamePacket = new GamePacket();
        gamePacket.setCommand(command);
        gamePacket.setSeq(seq);
        gamePacket.setCode(code);
        gamePacket.setFlags(flags);
        gamePacket.setBody(body);
        out.add(gamePacket);
    }
}
