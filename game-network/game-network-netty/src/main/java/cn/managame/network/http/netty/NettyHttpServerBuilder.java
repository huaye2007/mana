package cn.managame.network.http.netty;

import cn.managame.network.http.IHttpHandler;
import cn.managame.network.server.NettyServer;
import cn.managame.network.server.NettyServerBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public final class NettyHttpServerBuilder {

    private final IHttpHandler handler;
    private final NettyServerBuilder serverBuilder;
    private NettyHttpProtocol protocol = NettyHttpProtocol.AUTO;
    private SslContext sslContext;
    private int maxContentLength = 1_048_576;
    private int maxInitialLineLength = 4_096;
    private int maxHeaderSize = 8_192;
    private int maxPendingRequests = 1_024;
    private long maxConcurrentStreams = 128;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Consumer<ChannelPipeline> beforeProtocol = ignored -> { };
    private Consumer<ChannelPipeline> applicationPipeline = ignored -> { };

    NettyHttpServerBuilder(IHttpHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.serverBuilder = NettyServer.custom(new io.netty.channel.ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                throw new IllegalStateException("HTTP initializer has not been installed");
            }
        });
    }

    public NettyHttpServerBuilder bind(String host, int port) {
        serverBuilder.bind(host, port);
        return this;
    }

    public NettyHttpServerBuilder bind(int port) {
        serverBuilder.bind(port);
        return this;
    }

    public NettyHttpServerBuilder bind(SocketAddress address) {
        serverBuilder.bind(address);
        return this;
    }

    public NettyHttpServerBuilder protocol(NettyHttpProtocol protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        return this;
    }

    /** The supplied Netty context must advertise h2/http1.1 through ALPN when those protocols are enabled. */
    public NettyHttpServerBuilder sslContext(SslContext sslContext) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        return this;
    }

    public NettyHttpServerBuilder maxContentLength(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        this.maxContentLength = maxContentLength;
        return this;
    }

    public NettyHttpServerBuilder maxInitialLineLength(int maxInitialLineLength) {
        this.maxInitialLineLength = positive(maxInitialLineLength, "maxInitialLineLength");
        return this;
    }

    public NettyHttpServerBuilder maxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = positive(maxHeaderSize, "maxHeaderSize");
        return this;
    }

    /** Maximum number of decoded HTTP/1 requests waiting for ordered responses per connection. */
    public NettyHttpServerBuilder maxPendingRequests(int maxPendingRequests) {
        this.maxPendingRequests = positive(maxPendingRequests, "maxPendingRequests");
        return this;
    }

    /** Maximum concurrently active HTTP/2 streams per connection. */
    public NettyHttpServerBuilder maxConcurrentStreams(long maxConcurrentStreams) {
        if (maxConcurrentStreams <= 0) {
            throw new IllegalArgumentException("maxConcurrentStreams must be positive");
        }
        this.maxConcurrentStreams = maxConcurrentStreams;
        return this;
    }

    public NettyHttpServerBuilder requestTimeout(Duration requestTimeout) {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        return this;
    }

    public NettyHttpServerBuilder beforeProtocol(Consumer<ChannelPipeline> configurer) {
        beforeProtocol = beforeProtocol.andThen(Objects.requireNonNull(configurer, "configurer"));
        return this;
    }

    /** Configures the HTTP/1 connection pipeline or every HTTP/2 stream pipeline. */
    public NettyHttpServerBuilder pipeline(Consumer<ChannelPipeline> configurer) {
        applicationPipeline = applicationPipeline.andThen(Objects.requireNonNull(configurer, "configurer"));
        return this;
    }

    public NettyHttpServerBuilder eventLoopThreads(int bossThreads, int workerThreads) {
        serverBuilder.eventLoopThreads(bossThreads, workerThreads);
        return this;
    }

    public NettyHttpServerBuilder eventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                                   boolean shutdownOnStop) {
        serverBuilder.eventLoopGroups(bossGroup, workerGroup, shutdownOnStop);
        return this;
    }

    public NettyHttpServerBuilder serverChannel(Class<? extends ServerSocketChannel> channelClass) {
        serverBuilder.serverChannel(channelClass);
        return this;
    }

    public NettyHttpServerBuilder bootstrap(Consumer<ServerBootstrap> configurer) {
        serverBuilder.bootstrap(configurer);
        return this;
    }

    public NettyHttpServer build() {
        NettyHttpChannelInitializer initializer = new NettyHttpChannelInitializer(
                handler, protocol, sslContext, maxContentLength, maxInitialLineLength, maxHeaderSize,
                maxPendingRequests, maxConcurrentStreams, requestTimeout,
                beforeProtocol, applicationPipeline);
        serverBuilder.initializer(initializer);
        return new NettyHttpServer(serverBuilder.build());
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
