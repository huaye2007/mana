package cn.managame.network.netty.transport;

import cn.managame.network.*;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Http server using native Bootstrap and ChannelGroup lifecycle. */
public final class HttpNetworkServer implements NetworkServer {
    private final NetworkResources resources;
    private final boolean ownsResources;
    private final Settings settings;
    private final Map<String, InetSocketAddress> addresses;
    private final Map<ChannelOption<?>, Object> listenerOptions;
    private final Map<String, InetSocketAddress> bound = new ConcurrentHashMap<>();
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private volatile boolean stopped;

    private final Consumer<ChannelPipeline> httpPipeline;
    private final Consumer<HttpDecoderConfig> httpDecoder;
    private final int aggregation;
    private final String contextPath;

    private HttpNetworkServer(Builder b) {
        settings = new Settings(b, NetworkOptions.START_TIMEOUT, NetworkOptions.STOP_TIMEOUT);
        if (b.addresses.isEmpty())
            throw new IllegalArgumentException("At least one listen address required");

        httpPipeline = b.httpPipeline;
        httpDecoder = b.httpDecoder;
        aggregation = b.aggregation;
        contextPath = b.contextPath;
        ownsResources = b.resources == null;
        resources = ownsResources ? NetworkResources.create() : b.resources;
        addresses = Collections.unmodifiableMap(new LinkedHashMap<>(b.addresses));
        listenerOptions = Map.copyOf(b.listenerOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, InetSocketAddress> boundAddresses() {
        return Map.copyOf(bound);
    }

    @Override
    public void start() {
        resources.checkControlThread();
        synchronized (this) {
            if (stopped) throw new IllegalStateException("Server stopped");
            if (!bound.isEmpty()) return;
            try {
                var bootstrap =
                        new ServerBootstrap()
                                .group(resources.acceptor(), resources.http())
                                .channelFactory(resources.serverChannel)
                                .childHandler(
                                        new ChannelInitializer<Channel>() {
                                            @Override
                                            protected void initChannel(Channel ch)
                                                    throws Exception {
                                                channels.add(ch);
                                                if (stopped) {
                                                    ch.close();
                                                    return;
                                                }
                                                initPipeline(ch);
                                            }
                                        });
                configure(bootstrap);
                long deadline =
                        System.nanoTime() + settings.get(NetworkOptions.START_TIMEOUT).toNanos();
                for (var entry : addresses.entrySet()) {
                    var bind = bootstrap.bind(entry.getValue());
                    if (bind.channel().isOpen()) channels.add(bind.channel());
                    Waits.netty(bind, deadline, "bind " + entry.getKey());
                    bound.put(entry.getKey(), (InetSocketAddress) bind.channel().localAddress());
                }
            } catch (RuntimeException failure) {
                boolean interrupted = Thread.interrupted();
                try {
                    stop();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
                throw failure;
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configure(ServerBootstrap bootstrap) {
        listenerOptions.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
        settings.nativeOptions.forEach(
                (key, value) -> bootstrap.childOption((ChannelOption) key, value));
    }

    @Override
    public void stop() {
        resources.checkControlThread();
        synchronized (this) {
            stopped = true;
            Waits.netty(
                    channels.close(),
                    System.nanoTime() + settings.get(NetworkOptions.STOP_TIMEOUT).toNanos(),
                    "stop");
            if (ownsResources) resources.close();
        }
    }

    private void initPipeline(Channel ch) {
        var config = new HttpDecoderConfig();
        httpDecoder.accept(config);
        ch.pipeline().addLast("network.httpCodec", new HttpServerCodec(config));
        if (!contextPath.isEmpty())
            ch.pipeline()
                    .addLast("network.httpContextPath", new HttpContextPathHandler(contextPath));
        if (aggregation > 0)
            ch.pipeline().addLast("network.httpAggregate", new HttpObjectAggregator(aggregation));
        if (httpPipeline != null) httpPipeline.accept(ch.pipeline());
        else
            ch.pipeline()
                    .addLast(
                            "network.notFound",
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    try {
                                        if (msg instanceof HttpRequest request) {
                                            var response =
                                                    new DefaultFullHttpResponse(
                                                            HttpVersion.HTTP_1_1,
                                                            HttpResponseStatus.NOT_FOUND);
                                            HttpUtil.setContentLength(response, 0);
                                            boolean keep = HttpUtil.isKeepAlive(request);
                                            HttpUtil.setKeepAlive(response, keep);
                                            var write = ctx.writeAndFlush(response);
                                            if (!keep)
                                                write.addListener(ChannelFutureListener.CLOSE);
                                        }
                                    } finally {
                                        ReferenceCountUtil.release(msg);
                                    }
                                }
                            });
        ch.pipeline()
                .addLast(
                        "network.httpErrors",
                        new ChannelInboundHandlerAdapter() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                                resources.diagnose("HTTP handler failure", t);
                                ctx.close();
                            }
                        });
    }

    public static final class Builder extends ServerBuilder<Builder> {
        private Consumer<ChannelPipeline> httpPipeline;
        private Consumer<HttpDecoderConfig> httpDecoder = c -> {};
        private int aggregation = 1024 * 1024;
        private String contextPath = "";

        /**
         * Sets a literal URL path prefix, for example /game. Empty or / means the root context. A
         * trailing slash is removed. Matching requests reach httpPipeline with the prefix removed
         * from their URI; the raw query is preserved. Other paths receive 404.
         */
        public Builder contextPath(String value) {
            Objects.requireNonNull(value);
            if (value.isEmpty() || value.equals("/")) {
                contextPath = "";
                return this;
            }
            if (!value.startsWith("/")
                    || value.contains("//")
                    || !value.matches("/[A-Za-z0-9._~/-]+"))
                throw new IllegalArgumentException("Expected a literal context path such as /game");
            for (var segment : value.split("/"))
                if (segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("Context path cannot contain dot segments");
            contextPath = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            return this;
        }

        public Builder httpPipeline(Consumer<ChannelPipeline> value) {
            httpPipeline = Objects.requireNonNull(value);
            return this;
        }

        public Builder httpDecoder(Consumer<HttpDecoderConfig> value) {
            httpDecoder = Objects.requireNonNull(value);
            return this;
        }

        public Builder httpAggregation(int bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Negative aggregation");
            aggregation = bytes;
            return this;
        }

        public HttpNetworkServer build() {
            return new HttpNetworkServer(this);
        }
    }
}
