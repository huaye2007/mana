package cn.managame.dev.client;

import cn.managame.dev.protocol.GamePacketConstant;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 客户端入站解码器，按外网帧格式把服务端回包还原成 {@link ClientResponse}。
 *
 * <p>帧格式与服务端 {@code GamePacketDecoder} 对齐：
 * {@code bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)}，
 * 帧头固定 {@link GamePacketConstant#HEAD_LENGTH} 字节。半包（帧未到齐）时
 * {@code markReaderIndex/resetReaderIndex} 保持流对齐，等后续字节到达再解。</p>
 *
 * <p>与服务端解码器的差异：这里不查 {@code CommandRegistry}、不反序列化 body，
 * 只把 body 原始字节交给上层，避免客户端依赖“回包 command → 业务类型”的映射。</p>
 */
public class ClientFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int readableBytes = in.readableBytes();
        if (readableBytes < GamePacketConstant.HEAD_LENGTH) {
            return;
        }
        in.markReaderIndex();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > GamePacketConstant.MAX_BODY_LENGTH) {
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
        byte[] body = new byte[bodyLength];
        in.readBytes(body);

        out.add(new ClientResponse(command, seq, code, flags, body));
    }
}
