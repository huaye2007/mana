package cn.managame.network.http;

import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.OrderedEventExecutor;
import java.net.SocketAddress;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiConsumer;

/** HTTP/1.1 server configuration; supplied groups remain caller-owned. */
public final class HttpServerBuilder {
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    public static final int DEFAULT_MAX_HEADER_SIZE = 8192;
    public static final long DEFAULT_READ_TIMEOUT_MILLIS = 30_000;

    SocketAddress address;
    BiConsumer<FullHttpRequest, HttpResponseCallback> handler = (request, callback) ->
            callback.onResponse(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND));
    final List<Consumer<ChannelPipeline>> pipelines = new ArrayList<>();
    EventLoopGroup boss, worker;
    EventExecutorGroup executor;
    ChannelFactory<? extends ServerChannel> factory = NioServerSocketChannel::new;
    final Map<ChannelOption<?>, Object> options = new LinkedHashMap<>(), childOptions = new LinkedHashMap<>();
    int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
    int maxHeaderSize = DEFAULT_MAX_HEADER_SIZE;
    long readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;

    HttpServerBuilder() {}

    public HttpServerBuilder bindAddress(SocketAddress address) { this.address = Objects.requireNonNull(address); return this; }
    /** Optional fallback; requests are borrowed until return and returned response ownership transfers. Default: empty 404. */
    public HttpServerBuilder handler(Function<FullHttpRequest, FullHttpResponse> handler) {
        Objects.requireNonNull(handler);
        this.handler = (request, callback) -> callback.onResponse(handler.apply(request)); return this;
    }
    /**
     * Optional asynchronous fallback. Requests are borrowed until this callback returns; retain/copy
     * before asynchronous use. The callback may be completed on any thread. Last handler/asyncHandler wins.
     */
    public HttpServerBuilder asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback> handler) {
        this.handler = Objects.requireNonNull(handler); return this;
    }
    /** Runs after HTTP aggregation and before the fallback. Add native handlers with addLast; use addFirst for TLS. */
    public HttpServerBuilder pipeline(Consumer<ChannelPipeline> configurer) { pipelines.add(Objects.requireNonNull(configurer)); return this; }
    /** Offload HTTP processing to an ordered, caller-owned executor group. */
    public HttpServerBuilder executorGroup(EventExecutorGroup group) {
        Objects.requireNonNull(group);
        for (var executor : group)
            if (!(executor instanceof OrderedEventExecutor))
                throw new IllegalArgumentException("HTTP executors must preserve per-connection ordering");
        executor = group; return this;
    }
    public HttpServerBuilder maxContentLength(int bytes) { maxContentLength = positive(bytes); return this; }
    public HttpServerBuilder maxInitialLineLength(int bytes) { maxInitialLineLength = positive(bytes); return this; }
    public HttpServerBuilder maxHeaderSize(int bytes) { maxHeaderSize = positive(bytes); return this; }
    /** No inbound bytes for this interval closes the connection; zero disables this I/O timeout. */
    public HttpServerBuilder readTimeoutMillis(long millis) {
        if (millis < 0) throw new IllegalArgumentException("readTimeoutMillis must not be negative");
        readTimeoutMillis = millis; return this;
    }
    public HttpServerBuilder bossGroup(EventLoopGroup group) { boss = Objects.requireNonNull(group); return this; }
    public HttpServerBuilder workerGroup(EventLoopGroup group) { worker = Objects.requireNonNull(group); return this; }
    public HttpServerBuilder channelFactory(ChannelFactory<? extends ServerChannel> factory) {
        this.factory = Objects.requireNonNull(factory); return this;
    }
    public <T> HttpServerBuilder option(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value)); options.put(option, value); return this;
    }
    public <T> HttpServerBuilder childOption(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value)); childOptions.put(option, value); return this;
    }
    public HttpServer build() {
        if (address == null)
            throw new IllegalStateException("bindAddress is required");
        if (executor != null && Boolean.FALSE.equals(childOptions.get(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP)))
            throw new IllegalArgumentException("HTTP offloading requires one executor per group per connection");
        return new HttpServer(this);
    }
    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("HTTP size limits must be positive");
        return value;
    }
}
