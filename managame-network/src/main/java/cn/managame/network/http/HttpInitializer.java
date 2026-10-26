package cn.managame.network.http;

import cn.managame.network.netty.HandlerChain;
import cn.managame.network.netty.PipelineNames;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.SslContext;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the pipeline of every accepted HTTP/1.1 connection, for a {@link cn.managame.network.netty.NetworkServer}
 * or an application's own ServerBootstrap:
 *
 * <pre>{@code
 * HttpInitializer admin = HttpInitializer.http()
 *         .contextPath("/admin")
 *         .handler(exchange -> exchange.respond(HttpResponseStatus.OK))
 *         .build();
 * NetworkServer server = NetworkServer.start(8080, admin);
 * }</pre>
 *
 * Requests arrive whole, bodies aggregated up to {@link Builder#maxContentLength}; larger ones are answered with
 * 413 and the connection closed. Keep-alive follows the request and the response's Connection header. Responses
 * go out in request order: after a request has been read, the connection reads nothing more until its response
 * is written. The layout is described in {@link PipelineNames}.
 */
public final class HttpInitializer extends ChannelInitializer<Channel> {
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    private static final Set<String> RESERVED = Set.of(PipelineNames.SSL, PipelineNames.HTTP_CODEC, PipelineNames.HTTP_FLOW,
            PipelineNames.HTTP_EXCHANGE, PipelineNames.HTTP_KEEP_ALIVE, PipelineNames.HTTP_AGGREGATOR, PipelineNames.HTTP_HANDLER);
    private final SslContext ssl;
    private final int maxContentLength;
    private final HandlerChain front, application;
    private final HttpDispatchHandler dispatcher;

    private HttpInitializer(Builder b) {
        ssl = b.ssl;
        maxContentLength = b.maxContentLength;
        front = b.front.build();
        application = b.application.build();
        dispatcher = new HttpDispatchHandler(b.handler, b.contextPath);
    }

    public static Builder http() { return new Builder(null); }

    /** HTTP over TLS. See {@link cn.managame.network.netty.NetworkSsl}. */
    public static Builder https(SslContext ssl) {
        if (!Objects.requireNonNull(ssl, "ssl").isServer()) throw new IllegalArgumentException("Server SslContext required");
        return new Builder(ssl);
    }

    @Override protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        front.addTo(pipeline);
        if (ssl != null) pipeline.addLast(PipelineNames.SSL, ssl.newHandler(channel.alloc()));
        pipeline.addLast(PipelineNames.HTTP_CODEC, new HttpServerCodec());
        pipeline.addLast(PipelineNames.HTTP_FLOW, new FlowControlHandler());
        pipeline.addLast(PipelineNames.HTTP_EXCHANGE, new HttpExchangeGate());
        pipeline.addLast(PipelineNames.HTTP_KEEP_ALIVE, new HttpServerKeepAliveHandler());
        pipeline.addLast(PipelineNames.HTTP_AGGREGATOR, new Aggregator(maxContentLength));
        application.addTo(pipeline);
        pipeline.addLast(PipelineNames.HTTP_HANDLER, dispatcher);
    }

    @Override public String toString() { return ssl == null ? "http" : "https"; }

    /** Closes after 413 and after a refused 100-continue, so no request body is left half read. */
    private static final class Aggregator extends HttpObjectAggregator {
        Aggregator(int maxContentLength) { super(maxContentLength, true); }

        @Override protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage oversized) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);
            HttpUtil.setContentLength(response, 0);
            HttpUtil.setKeepAlive(response, false);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Handlers go to two sections of each pipeline: {@code addFront} ahead of TLS and HTTP decoding, seeing raw
     * bytes, for example an IP filter; {@code addLast} after aggregation, seeing FullHttpRequest. A handler in
     * the latter that answers a request itself must answer every request it consumes.
     */
    public static final class Builder {
        private final SslContext ssl;
        private HttpHandler handler;
        private String contextPath = "";
        private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
        private final HandlerChain.Builder front = HandlerChain.builder(), application = HandlerChain.builder();
        private final Set<String> names = new HashSet<>();

        private Builder(SslContext ssl) { this.ssl = ssl; }

        /** Required. */
        public Builder handler(HttpHandler handler) { this.handler = Objects.requireNonNull(handler, "handler"); return this; }

        /**
         * The path the handler serves, for example {@code /admin}; the root by default. Other paths are answered
         * with 404 without reaching the handler, which sees paths relative to it through {@link HttpExchange#path}.
         * A trailing slash is ignored; handlers in the addLast section still see every request.
         */
        public Builder contextPath(String path) {
            Objects.requireNonNull(path, "contextPath");
            if (!path.isEmpty() && (!path.startsWith("/") || path.contains("//") || path.contains("?")
                    || path.contains("#") || path.contains("*") || path.chars().anyMatch(Character::isWhitespace)))
                throw new IllegalArgumentException("contextPath must look like /admin: " + path);
            contextPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return this;
        }

        /** Largest request body; 1 MiB by default. */
        public Builder maxContentLength(int bytes) {
            if (bytes <= 0) throw new IllegalArgumentException("maxContentLength must be positive");
            maxContentLength = bytes;
            return this;
        }

        public Builder addFront(String name, ChannelHandler sharable) { front.addLast(name(name), sharable); return this; }
        public Builder addFront(String name, Supplier<? extends ChannelHandler> factory) { front.addLast(name(name), factory); return this; }
        public Builder addFront(HandlerChain chain) { chain.names().forEach(this::name); front.addLast(chain); return this; }

        public Builder addLast(String name, ChannelHandler sharable) { application.addLast(name(name), sharable); return this; }
        public Builder addLast(String name, Supplier<? extends ChannelHandler> factory) { application.addLast(name(name), factory); return this; }
        public Builder addLast(HandlerChain chain) { chain.names().forEach(this::name); application.addLast(chain); return this; }

        public HttpInitializer build() {
            if (handler == null) throw new IllegalStateException("handler is required");
            return new HttpInitializer(this);
        }

        private String name(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Handler name must not be blank");
            if (RESERVED.contains(name)) throw new IllegalArgumentException("Reserved handler name: " + name);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate handler name: " + name);
            return name;
        }
    }
}
