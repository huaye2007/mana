package cn.managame.network.http;

import io.netty.channel.*;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.List;
import java.io.IOException;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;

/** Each connection has one ordered HTTP pipeline, including automatic protocol responses. */
final class HttpServerTransport extends ChannelDuplexHandler {
    private boolean closing;

    static void configure(ChannelPipeline pipeline, EventExecutorGroup executor,
                          Function<FullHttpRequest, FullHttpResponse> handler,
                          int bodyLimit, int lineLimit, int headerLimit,
                          List<Consumer<ChannelPipeline>> configurers) {
        HttpServerTransport transport = new HttpServerTransport();
        pipeline.addLast(executor, "http-codec", new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(lineLimit).setMaxHeaderSize(headerLimit)));
        pipeline.addLast(executor, "http-validation", transport);
        pipeline.addLast(executor, "http-keep-alive", new HttpServerKeepAliveHandler());
        pipeline.addLast(executor, "http-aggregation", new HttpObjectAggregator(bodyLimit, true) {
            @Override protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage message) {
                transport.reject(ctx, REQUEST_ENTITY_TOO_LARGE);
            }
            @Override protected boolean closeAfterContinueResponse(Object response) {
                boolean close = super.closeAfterContinueResponse(response);
                if (close) transport.closing = true;
                return close;
            }
        });
        for (var configurer : configurers) configurer.accept(pipeline);
        // HTTP extensions share the codec's ordered executor; TLS/raw handlers before it may use the EventLoop.
        var httpExecutor = pipeline.context("http-codec").executor();
        boolean httpStarted = false;
        for (String name : pipeline.names()) {
            ChannelHandlerContext context = pipeline.context(name);
            if (context == null) continue; // Netty's tail has no user-addressable context.
            if (name.equals("http-codec")) httpStarted = true;
            if (httpStarted && context.executor() != httpExecutor)
                throw new IllegalArgumentException("HTTP handlers must use the same ordered executor; handler=" + name);
        }
        pipeline.addLast(executor, "http-application", new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                transport.exceptionCaught(ctx, cause);
            }
            @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                if (transport.closing || !ctx.channel().isActive()) return;
                if (!request.decoderResult().isSuccess()) {
                    transport.reject(ctx, BAD_REQUEST); return;
                }
                FullHttpResponse response;
                try {
                    response = handler.apply(request);
                    if (response == null) throw new IllegalStateException("HTTP handler returned null");
                    if (response.status().code() < 200) {
                        response.release();
                        throw new IllegalArgumentException("HTTP handler must return a final response");
                    }
                } catch (Throwable cause) {
                    HttpServer.log("HTTP handler failed", cause);
                    transport.reject(ctx, INTERNAL_SERVER_ERROR); return;
                }
                if (!ctx.channel().isActive()) { response.release(); return; }
                try {
                    response.setProtocolVersion(HttpVersion.HTTP_1_1);
                    response.headers().remove(TRANSFER_ENCODING);
                    response.trailingHeaders().clear();
                    int code = response.status().code();
                    int length = response.content().readableBytes();
                    if ((request.method().equals(HttpMethod.HEAD) || code == 304) && HttpUtil.isContentLengthSet(response)
                            && (response.headers().getAll(CONTENT_LENGTH).size() != 1 || HttpUtil.getContentLength(response) < 0))
                        throw new IllegalArgumentException("Invalid response content length");
                    if (code == 204 || code == 205 || code == 304) {
                        response.content().clear();
                        if (code == 204) response.headers().remove(CONTENT_LENGTH);
                        else if (code == 205) HttpUtil.setContentLength(response, 0);
                        else if (!HttpUtil.isContentLengthSet(response) && length != 0)
                            HttpUtil.setContentLength(response, length);
                    } else if (!request.method().equals(HttpMethod.HEAD) || !HttpUtil.isContentLengthSet(response)) {
                        HttpUtil.setContentLength(response, length);
                    }
                    boolean keepAlive = HttpUtil.isKeepAlive(request) && HttpUtil.isKeepAlive(response);
                    HttpUtil.setKeepAlive(response, keepAlive);
                    if (!keepAlive) transport.closing = true;
                } catch (Throwable cause) {
                    response.release();
                    HttpServer.log("Invalid HTTP response", cause);
                    transport.reject(ctx, INTERNAL_SERVER_ERROR); return;
                }
                ChannelFuture write = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                if (transport.closing) write.addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (closing || !ctx.channel().isActive()) { ReferenceCountUtil.release(message); return; }
        if (message instanceof HttpRequest request) {
            HttpResponseStatus error = null;
            if (!request.decoderResult().isSuccess()) error = BAD_REQUEST;
            else if (!request.protocolVersion().equals(HttpVersion.HTTP_1_1)) error = HTTP_VERSION_NOT_SUPPORTED;
            else if (request.headers().getAll(HOST).size() != 1 || request.headers().get(HOST).isBlank()) error = BAD_REQUEST;
            else if (request.method().equals(HttpMethod.CONNECT)) error = METHOD_NOT_ALLOWED;
            else if (request.headers().contains(UPGRADE)) error = BAD_REQUEST;
            if (error != null) {
                ReferenceCountUtil.release(message);
                reject(ctx, error); return;
            }
        }
        ctx.fireChannelRead(message);
    }

    private void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
        if (closing || !ctx.channel().isActive()) return;
        closing = true;
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setContentLength(response, 0);
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        // Native KeepAlive policy has already selected closure for extension-produced responses.
        if (message instanceof HttpResponse response && response.status().code() >= 200
                && !HttpUtil.isKeepAlive(response)) closing = true;
        ctx.write(message, promise);
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closing = true;
        if (cause instanceof IOException || cause instanceof ReadTimeoutException
                || cause instanceof CorruptedFrameException || cause instanceof TooLongFrameException
                || cause instanceof PrematureChannelClosureException) {
            if (HttpServer.LOG.isLoggable(System.Logger.Level.DEBUG))
                HttpServer.LOG.log(System.Logger.Level.DEBUG, "HTTP connection closed; peer="
                        + ctx.channel().remoteAddress() + "; type=" + cause.getClass().getSimpleName());
        } else HttpServer.log("HTTP pipeline failed", cause);
        ctx.close();
    }
}
