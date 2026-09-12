package cn.managame.network.netty;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

/** Per-channel HTTP context selection, before aggregation and application handlers. */
final class HttpContextPathHandler extends ChannelInboundHandlerAdapter {
    private final String contextPath;
    private boolean discarding;

    HttpContextPathHandler(String contextPath) {
        this.contextPath = contextPath;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) {
            String uri = request.uri();
            int query = uri.indexOf('?');
            String path = query < 0 ? uri : uri.substring(0, query);
            discarding = !(path.equals(contextPath) || path.startsWith(contextPath + "/"));
            if (discarding) {
                var response =
                        new DefaultFullHttpResponse(
                                request.protocolVersion(), HttpResponseStatus.NOT_FOUND);
                HttpUtil.setContentLength(response, 0);
                // A client waiting for 100 Continue may never send the rejected body.
                boolean keep =
                        HttpUtil.isKeepAlive(request) && !HttpUtil.is100ContinueExpected(request);
                HttpUtil.setKeepAlive(response, keep);
                var write = ctx.writeAndFlush(response);
                if (!keep) write.addListener(ChannelFutureListener.CLOSE);
            } else {
                String relative = path.substring(contextPath.length());
                request.setUri(
                        (relative.isEmpty() ? "/" : relative)
                                + (query < 0 ? "" : uri.substring(query)));
            }
        }
        if (discarding) {
            ReferenceCountUtil.release(msg);
            if (msg instanceof LastHttpContent) discarding = false;
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
