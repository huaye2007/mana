package cn.managame.network.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.*;
import java.util.List;

final class WebSocketBinaryFrameDecoder extends MessageToMessageDecoder<WebSocketFrame> {
    @Override protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if (frame instanceof BinaryWebSocketFrame binary) out.add(binary.content().retain());
        else if (frame instanceof TextWebSocketFrame || frame instanceof ContinuationWebSocketFrame) {
            ctx.writeAndFlush(new CloseWebSocketFrame(1003, "Binary messages only"))
                    .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
        // Protocol handlers consume ping/pong/close; do not expose transport frames.
    }
}