package cn.managame.network.http;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terminal handler of an HTTP pipeline: answers requests outside the context path with 404 and hands the
 * rest to the HttpHandler. The request is released after.
 */
@Sharable
final class HttpDispatchHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final System.Logger LOG = System.getLogger("cn.managame.network.http");
    private final HttpHandler handler;
    private final String contextPath;

    /** @param contextPath empty for the root, otherwise like {@code /admin} */
    HttpDispatchHandler(HttpHandler handler, String contextPath) {
        this.handler = handler;
        this.contextPath = contextPath;
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = request.decoderResult().isSuccess() ? pathBelowContext(request.uri()) : null;
        Exchange exchange = new Exchange(ctx, request, path);
        if (!request.decoderResult().isSuccess()) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
            HttpUtil.setKeepAlive(response, false);
            exchange.respond(response);
            return;
        }
        if (path == null) {
            exchange.respond(HttpResponseStatus.NOT_FOUND);
            return;
        }
        try {
            handler.handle(exchange);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "HTTP handler failed; " + request.method() + " " + request.uri(), cause);
            exchange.respond(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** The raw path below the context path, or null when the request is outside it. */
    private String pathBelowContext(String uri) {
        int end = uri.length();
        int query = uri.indexOf('?');
        if (query >= 0) end = query;
        int fragment = uri.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        String path = uri.substring(0, end);
        // Absolute form, as a proxy sends it: http://host:port/path
        int scheme = path.indexOf("://");
        if (!path.startsWith("/") && scheme > 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        if (contextPath.isEmpty()) return path;
        if (path.equals(contextPath)) return "/";
        return path.startsWith(contextPath) && path.charAt(contextPath.length()) == '/'
                ? path.substring(contextPath.length()) : null;
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (LOG.isLoggable(Level.DEBUG))
            LOG.log(Level.DEBUG, "Closing HTTP connection; peer=" + ctx.channel().remoteAddress() + "; " + cause);
        ctx.close();
    }

    private static final class Exchange implements HttpExchange {
        private final ChannelHandlerContext ctx;
        private final FullHttpRequest request;
        private final String path;
        private final AtomicBoolean responded = new AtomicBoolean();

        Exchange(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
            this.ctx = ctx;
            this.request = request;
            this.path = path;
        }

        @Override public FullHttpRequest request() { return request; }
        @Override public String path() { return path; }

        @Override public boolean respond(FullHttpResponse response) {
            Objects.requireNonNull(response, "response");
            if (response.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                response.release();
                throw new IllegalArgumentException("A final response is required, not " + response.status());
            }
            if (!responded.compareAndSet(false, true)) {
                response.release();
                return false;
            }
            int code = response.status().code();
            if (code != 204 && code != 304 && !HttpUtil.isContentLengthSet(response))
                HttpUtil.setContentLength(response, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return true;
        }

        @Override public SocketAddress remoteAddress() { return ctx.channel().remoteAddress(); }
    }
}
