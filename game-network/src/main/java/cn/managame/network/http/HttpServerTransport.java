package cn.managame.network.http;

import io.netty.channel.*;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;

/** Each connection has one ordered HTTP pipeline, including automatic protocol responses. */
final class HttpServerTransport extends ChannelDuplexHandler {
    private boolean closing;
    private boolean waitingResponse, requestEnded, responseFinished, writingFinal, pausedReads, draining, advanceScheduled;
    private final ArrayDeque<Object> pendingInput = new ArrayDeque<>();

    static void configure(ChannelPipeline pipeline, EventExecutorGroup executor,
                          BiConsumer<FullHttpRequest, HttpResponseCallback> handler,
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
                HttpResponseCallback callback = transport.new ResponseCallback(ctx, request);
                try {
                    handler.accept(request, callback);
                } catch (Throwable cause) {
                    if (!callback.onFail(cause)) HttpServer.log("HTTP handler failed after completion", cause);
                }
            }
        });
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (closing || !ctx.channel().isActive()) { ReferenceCountUtil.release(message); return; }
        // Pause subsequent requests before validation/aggregation can emit 100, 413 or 417.
        if (waitingResponse && requestEnded) { pendingInput.addLast(message); return; }
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
            waitingResponse = true;
            requestEnded = responseFinished = false;
        }
        if (message instanceof LastHttpContent) {
            requestEnded = true;
            if (ctx.channel().config().isAutoRead()) {
                pausedReads = true;
                ctx.channel().config().setAutoRead(false);
            }
        }
        ctx.fireChannelRead(message);
        advance(ctx);
    }

    private void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
        if (closing || !ctx.channel().isActive()) return;
        closing = true;
        releasePending();
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setContentLength(response, 0);
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        // Native KeepAlive policy has already selected closure for extension-produced responses.
        if (message instanceof HttpResponse response && response.status().code() >= 200) {
            writingFinal = true;
            if (!HttpUtil.isKeepAlive(response)) { closing = true; releasePending(); }
        }
        if (writingFinal && message instanceof LastHttpContent) {
            writingFinal = false;
            promise = promise.unvoid();
            promise.addListener(future -> execute(ctx, () -> {
                if (!future.isSuccess()) { closing = true; releasePending(); ctx.close(); return; }
                responseFinished = true;
                advance(ctx);
            }, null));
        }
        ctx.write(message, promise);
    }

    private void advance(ChannelHandlerContext ctx) {
        if (!waitingResponse || !requestEnded || !responseFinished || closing || advanceScheduled) return;
        advanceScheduled = true;
        // Defer to avoid nesting later business handlers inside a synchronous response call.
        execute(ctx, () -> drain(ctx), null, true);
    }

    private void drain(ChannelHandlerContext ctx) {
        advanceScheduled = false;
        if (draining) return;
        if (waitingResponse && requestEnded && responseFinished)
            waitingResponse = requestEnded = responseFinished = false;
        draining = true;
        try {
            while (!pendingInput.isEmpty() && !closing && ctx.channel().isActive()
                    && !(waitingResponse && requestEnded)) channelRead(ctx, pendingInput.removeFirst());
            if (closing || !ctx.channel().isActive()) releasePending();
            else if (!(waitingResponse && requestEnded) && pausedReads) {
                pausedReads = false;
                ctx.channel().config().setAutoRead(true);
            }
        } finally { draining = false; }
    }

    private void releasePending() {
        Object message;
        while ((message = pendingInput.pollFirst()) != null) ReferenceCountUtil.release(message);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        closing = true;
        releasePending();
        ctx.fireChannelInactive();
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) { releasePending(); }

    private static void execute(ChannelHandlerContext ctx, Runnable action, FullHttpResponse response) {
        execute(ctx, action, response, false);
    }

    private static void execute(ChannelHandlerContext ctx, Runnable action, FullHttpResponse response, boolean defer) {
        if (!defer && ctx.executor().inEventLoop()) { action.run(); return; }
        try { ctx.executor().execute(action); }
        catch (RuntimeException rejected) {
            ReferenceCountUtil.release(response);
            ctx.close();
        }
    }

    private final class ResponseCallback implements HttpResponseCallback {
        private final ChannelHandlerContext ctx;
        private final boolean head, keepAlive;
        private final AtomicBoolean completed = new AtomicBoolean();

        ResponseCallback(ChannelHandlerContext ctx, FullHttpRequest request) {
            this.ctx = ctx;
            head = request.method().equals(HttpMethod.HEAD);
            keepAlive = HttpUtil.isKeepAlive(request);
        }

        @Override public boolean onResponse(FullHttpResponse response) {
            Objects.requireNonNull(response);
            if (!completed.compareAndSet(false, true)) { response.release(); return false; }
            if (!ctx.channel().isActive()) { response.release(); return true; }
            execute(ctx, () -> send(response), response);
            return true;
        }

        @Override public boolean onFail(Throwable cause) {
            Objects.requireNonNull(cause);
            if (!completed.compareAndSet(false, true)) return false;
            execute(ctx, () -> {
                HttpServer.log("HTTP handler failed", cause);
                reject(ctx, INTERNAL_SERVER_ERROR);
            }, null);
            return true;
        }

        private void send(FullHttpResponse response) {
            if (closing || !ctx.channel().isActive()) { response.release(); return; }
            try {
                if (response.status().code() < 200) throw new IllegalArgumentException("HTTP handler must provide a final response");
                response.setProtocolVersion(HttpVersion.HTTP_1_1);
                response.headers().remove(TRANSFER_ENCODING);
                response.trailingHeaders().clear();
                int code = response.status().code();
                int length = response.content().readableBytes();
                if ((head || code == 304) && HttpUtil.isContentLengthSet(response)
                        && (response.headers().getAll(CONTENT_LENGTH).size() != 1 || HttpUtil.getContentLength(response) < 0))
                    throw new IllegalArgumentException("Invalid response content length");
                if (code == 204 || code == 205 || code == 304) {
                    response.content().clear();
                    if (code == 204) response.headers().remove(CONTENT_LENGTH);
                    else if (code == 205) HttpUtil.setContentLength(response, 0);
                    else if (!HttpUtil.isContentLengthSet(response) && length != 0) HttpUtil.setContentLength(response, length);
                } else if (!head || !HttpUtil.isContentLengthSet(response)) HttpUtil.setContentLength(response, length);
                HttpUtil.setKeepAlive(response, keepAlive && HttpUtil.isKeepAlive(response));
                if (!HttpUtil.isKeepAlive(response)) { closing = true; releasePending(); }
            } catch (Throwable cause) {
                response.release();
                HttpServer.log("Invalid HTTP response", cause);
                reject(ctx, INTERNAL_SERVER_ERROR); return;
            }
            ChannelFuture write = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (closing) write.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closing = true;
        releasePending();
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
