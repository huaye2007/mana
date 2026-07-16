package cn.managame.network.http.netty;

import cn.managame.network.http.HttpRequest;
import cn.managame.network.http.HttpResponse;
import cn.managame.network.http.IHttpHandler;
import cn.managame.network.http.IHttpResponder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class NettyHttpDispatcher extends SimpleChannelInboundHandler<io.netty.handler.codec.http.FullHttpRequest> {

    private static final AsciiString KEEP_ALIVE = AsciiString.cached("keep-alive");
    private static final AsciiString PROXY_CONNECTION = AsciiString.cached("proxy-connection");
    private static final AttributeKey<Http1ResponseQueue> HTTP1_RESPONSE_QUEUE =
            AttributeKey.valueOf(NettyHttpDispatcher.class, "http1ResponseQueue");

    private final IHttpHandler handler;
    private final boolean http2;

    NettyHttpDispatcher(IHttpHandler handler, boolean http2) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.http2 = http2;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context,
                                io.netty.handler.codec.http.FullHttpRequest message) {
        boolean keepAlive = http2 || HttpUtil.isKeepAlive(message);
        boolean headRequest = "HEAD".equals(message.method().name());
        Http1ResponseQueue.Slot responseSlot = http2 ? null : http1ResponseQueue(context.channel()).register(keepAlive);
        NettyHttpResponder responder = new NettyHttpResponder(
                context, keepAlive, headRequest, http2, responseSlot);
        if (!message.decoderResult().isSuccess()) {
            responder.send(HttpResponse.empty(400));
            return;
        }

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

    private static final class NettyHttpResponder implements IHttpResponder {
        private final ChannelHandlerContext context;
        private final boolean keepAlive;
        private final boolean headRequest;
        private final boolean http2;
        private final Http1ResponseQueue.Slot responseSlot;
        private final AtomicBoolean completed = new AtomicBoolean();

        private NettyHttpResponder(ChannelHandlerContext context, boolean keepAlive,
                                   boolean headRequest, boolean http2,
                                   Http1ResponseQueue.Slot responseSlot) {
            this.context = context;
            this.keepAlive = keepAlive;
            this.headRequest = headRequest;
            this.http2 = http2;
            this.responseSlot = responseSlot;
        }

        @Override
        public void send(HttpResponse response) {
            Objects.requireNonNull(response, "response");
            complete(() -> write(response));
        }

        @Override
        public void fail(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            complete(() -> write(HttpResponse.empty(500)));
        }

        private void failIfOpen(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            if (completed.compareAndSet(false, true)) {
                dispatch(() -> write(HttpResponse.empty(500)));
            }
        }

        private void complete(Runnable action) {
            if (!completed.compareAndSet(false, true)) {
                throw new IllegalStateException("HTTP response has already been completed");
            }
            dispatch(action);
        }

        private void dispatch(Runnable action) {
            Runnable protocolAwareAction = http2
                    ? action
                    : () -> http1ResponseQueue(context.channel()).complete(responseSlot, action);
            if (context.executor().inEventLoop()) {
                protocolAwareAction.run();
            } else {
                context.executor().execute(protocolAwareAction);
            }
        }

        private void write(HttpResponse response) {
            byte[] body = response.body();
            boolean bodyAllowed = !headRequest && response.status() >= 200
                    && response.status() != 204 && response.status() != 304;
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.status()),
                    bodyAllowed ? Unpooled.wrappedBuffer(body) : Unpooled.EMPTY_BUFFER);
            response.headers().forEach((name, values) -> values.forEach(
                    value -> nettyResponse.headers().add(name, value)));

            if (http2) {
                removeHttp2ProhibitedHeaders(nettyResponse);
            } else {
                HttpUtil.setKeepAlive(nettyResponse, keepAlive);
            }
            if (!nettyResponse.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                HttpUtil.setContentLength(nettyResponse, headRequest ? body.length : nettyResponse.content().readableBytes());
            }

            if (keepAlive) {
                context.writeAndFlush(nettyResponse);
            } else {
                context.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE);
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

        private Slot register(boolean keepAlive) {
            Slot slot = new Slot(keepAlive);
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
            private Runnable action;

            private Slot(boolean keepAlive) {
                this.keepAlive = keepAlive;
            }
        }
    }
}
