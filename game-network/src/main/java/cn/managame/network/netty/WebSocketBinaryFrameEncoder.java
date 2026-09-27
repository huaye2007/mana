package cn.managame.network.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;

final class WebSocketBinaryFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    @Override protected void encode(ChannelHandlerContext ctx, ByteBuf message, List<Object> out) {
        out.add(new BinaryWebSocketFrame(message.retain()));
    }
}