package cn.managame.network.netty;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

/** Exact raw path matching while leaving query/header information available to the handshaker. */
final class WebSocketPathHandler extends ChannelInboundHandlerAdapter {
    private final String path;
    WebSocketPathHandler(String path) { this.path = path; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof HttpRequest request) {
            String target = request.uri();
            int query = target.indexOf('?');
            String requestPath = query < 0 ? target : target.substring(0, query);
            if (!path.equals(requestPath)) {
                ReferenceCountUtil.release(message);
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        ctx.fireChannelRead(message);
    }
}