package cn.managame.network.http.netty;

import cn.managame.network.http.HttpRequest;
import cn.managame.network.http.HttpResponse;
import cn.managame.network.http.IHttpHandler;
import cn.managame.network.http.IHttpResponder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NettyHttpDispatcher extends SimpleChannelInboundHandler<io.netty.handler.codec.http.FullHttpRequest> {

    private static final AsciiString KEEP_ALIVE = AsciiString.cached("keep-alive");
    private static final AsciiString PROXY_CONNECTION = AsciiString.cached("proxy-connection");
    private static final AttributeKey<Http1ResponseQueue> HTTP1_RESPONSE_QUEUE =
            AttributeKey.valueOf(NettyHttpDispatcher.class, "http1ResponseQueue");

    private final IHttpHandler handler;
    private final boolean http2;
    private final Duration requestTimeout;
    private final int maxPendingRequests;

    NettyHttpDispatcher(IHttpHandler handler, boolean http2) {
        this(handler, http2, Duration.ofSeconds(30), 1_024);
    }

    NettyHttpDispatcher(IHttpHandler handler, boolean http2, Duration requestTimeout,
                        int maxPendingRequests) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.http2 = http2;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.maxPendingRequests = maxPendingRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context,
                                io.netty.handler.codec.http.FullHttpRequest message) {
        boolean keepAlive = http2 || HttpUtil.isKeepAlive(message);
        boolean headRequest = "HEAD".equals(message.method().name());
        Http1ResponseQueue.Slot responseSlot = null;
        if (!http2) {
            responseSlot = http1ResponseQueue(context.channel()).register(
                    context.channel(), keepAlive, maxPendingRequests);
            if (responseSlot == null) {
                context.close();
                return;
            }
            if (responseSlot.overflow()) {
                new NettyHttpResponder(context, false, headRequest, false, responseSlot, false)
                        .send(HttpResponse.empty(429));
                return;
            }
        }

        if (!message.decoderResult().isSuccess()) {
            new NettyHttpResponder(context, false, headRequest, http2, responseSlot, false)
                    .send(HttpResponse.empty(400));
            return;
        }
        NettyHttpResponder responder = new NettyHttpResponder(
                context, keepAlive, headRequest, http2, responseSlot, true);
        HttpRequest request = new HttpRequest(
                message.method().name(),
                message.uri(),
                copyHeaders(message.headers()),
                ByteBufUtil.getBytes(message.content()),
                remoteAddress(context.channel()));
        try {
            handler.handle(request, responder);
        } catch (Throwable failure) {
            responder.failIfOpen(failure);
        }
    }

    private static Map<String, List<String>> copyHeaders(io.netty.handler.codec.http.HttpHeaders headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach(entry -> result.computeIfAbsent(
                entry.getKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entry.getValue()));
        return result;
    }

    private static String remoteAddress(Channel channel) {
        Channel current = channel;
        while (current != null) {
            SocketAddress address = current.remoteAddress();
            if (address != null) {
                return address.toString();
            }
            current = current.parent();
        }
        return "unknown";
    }

    private static Http1ResponseQueue http1ResponseQueue(Channel channel) {
        Http1ResponseQueue queue = channel.attr(HTTP1_RESPONSE_QUEUE).get();
        if (queue != null) {
            return queue;
        }
        Http1ResponseQueue created = new Http1ResponseQueue();
        Http1ResponseQueue existing = channel.attr(HTTP1_RESPONSE_QUEUE).setIfAbsent(created);
        return existing == null ? created : existing;
    }

    private final class NettyHttpResponder implements IHttpResponder {
        private final ChannelHandlerContext context;
        private final boolean keepAlive;
        private final boolean headRequest;
        private final boolean responseIsHttp2;
        private final Http1ResponseQueue.Slot responseSlot;
        private final boolean guarded;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final ChannelFutureListener closeListener;
        private volatile ScheduledFuture<?> timeoutFuture;

        private NettyHttpResponder(ChannelHandlerContext context, boolean keepAlive,
                                   boolean headRequest, boolean responseIsHttp2,
                                   Http1ResponseQueue.Slot responseSlot, boolean guarded) {
            this.context = context;
            this.keepAlive = keepAlive;
            this.headRequest = headRequest;
            this.responseIsHttp2 = responseIsHttp2;
            this.responseSlot = responseSlot;
            this.guarded = guarded;
            this.closeListener = ignored -> abandon();
            if (guarded) {
                context.channel().closeFuture().addListener(closeListener);
                timeoutFuture = context.executor().schedule(
                        () -> completeIfOpen(HttpResponse.empty(408)),
                        requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
                if (completed.get()) {
                    timeoutFuture.cancel(false);
                }
            }
        }

        @Override
        public void send(HttpResponse response) {
            Objects.requireNonNull(response, "response");
            complete(response);
        }

        @Override
        public void fail(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            complete(HttpResponse.empty(500));
        }

        private void failIfOpen(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            completeIfOpen(HttpResponse.empty(500));
        }

        private void complete(HttpResponse response) {
            if (!completed.compareAndSet(false, true)) {
                throw new IllegalStateException("HTTP response has already been completed");
            }
            cancelTimeout();
            removeCloseListener();
            dispatch(() -> write(response));
        }

        private void completeIfOpen(HttpResponse response) {
            if (completed.compareAndSet(false, true)) {
                cancelTimeout();
                removeCloseListener();
                dispatch(() -> write(response));
            }
        }

        private void dispatch(Runnable action) {
            Runnable protocolAwareAction = responseIsHttp2
                    ? action
                    : () -> http1ResponseQueue(context.channel()).complete(responseSlot, action);
            if (context.executor().inEventLoop()) {
                protocolAwareAction.run();
            } else {
                context.executor().execute(protocolAwareAction);
            }
        }

        private void write(HttpResponse response) {
            try {
                byte[] body = response.body();
                boolean bodyAllowed = !headRequest && response.status() >= 200
                        && response.status() != 204 && response.status() != 304;
                FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.status()),
                        bodyAllowed ? Unpooled.wrappedBuffer(body) : Unpooled.EMPTY_BUFFER);
                response.headers().forEach((name, values) -> values.forEach(
                        value -> nettyResponse.headers().add(name, value)));

                if (responseIsHttp2) {
                    removeHttp2ProhibitedHeaders(nettyResponse);
                } else {
                    HttpUtil.setKeepAlive(nettyResponse, keepAlive);
                }
                if (response.status() == 204) {
                    nettyResponse.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
                    nettyResponse.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                } else if (!nettyResponse.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                    HttpUtil.setContentLength(nettyResponse,
                            headRequest || response.status() == 304
                                    ? body.length
                                    : nettyResponse.content().readableBytes());
                }

                ChannelFuture writeFuture = context.writeAndFlush(nettyResponse);
                if (!keepAlive) {
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                }
            } catch (Throwable failure) {
                context.close();
            }
        }

        private void abandon() {
            if (completed.compareAndSet(false, true)) {
                cancelTimeout();
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = timeoutFuture;
            if (current != null) {
                current.cancel(false);
            }
        }

        private void removeCloseListener() {
            if (guarded) {
                context.channel().closeFuture().removeListener(closeListener);
            }
        }

        private static void removeHttp2ProhibitedHeaders(FullHttpResponse response) {
            response.headers().remove(HttpHeaderNames.CONNECTION);
            response.headers().remove(KEEP_ALIVE);
            response.headers().remove(PROXY_CONNECTION);
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            response.headers().remove(HttpHeaderNames.UPGRADE);
        }
    }

    private static final class Http1ResponseQueue {
        private final Deque<Slot> slots = new ArrayDeque<>();
        private boolean accepting = true;

        private Slot register(Channel channel, boolean keepAlive, int maxPendingRequests) {
            if (!accepting) {
                return null;
            }
            if (slots.size() >= maxPendingRequests) {
                accepting = false;
                channel.config().setAutoRead(false);
                Slot overflow = new Slot(false, true);
                slots.addLast(overflow);
                return overflow;
            }
            Slot slot = new Slot(keepAlive, false);
            slots.addLast(slot);
            return slot;
        }

        private void complete(Slot slot, Runnable action) {
            slot.action = action;
            while (!slots.isEmpty()) {
                Slot head = slots.getFirst();
                if (head.action == null) {
                    return;
                }
                slots.removeFirst();
                head.action.run();
                if (!head.keepAlive) {
                    slots.clear();
                    return;
                }
            }
        }

        private static final class Slot {
            private final boolean keepAlive;
            private final boolean overflow;
            private Runnable action;

            private Slot(boolean keepAlive, boolean overflow) {
                this.keepAlive = keepAlive;
                this.overflow = overflow;
            }

            private boolean overflow() {
                return overflow;
            }
        }
    }
}
