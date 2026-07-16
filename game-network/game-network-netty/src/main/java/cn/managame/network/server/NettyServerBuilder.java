package cn.managame.network.server;

import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.NettyConnectionChannelHandler;
import cn.managame.network.handler.WebsocketConnectionChannelHandler;
import cn.managame.network.handler.codec.PacketToWebSocketFrameEncoder;
import cn.managame.network.handler.codec.WebSocketFrameToPacketDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Builds standard TCP/WebSocket servers while keeping native Netty customization available. */
public final class NettyServerBuilder {

    private enum Preset { TCP, WEBSOCKET, CUSTOM }

    private final Preset preset;
    private final IConnectionHandler connectionHandler;
    private final WebSocketServerProtocolConfig webSocketProtocol;
    private ChannelInitializer<SocketChannel> customInitializer;
    private SocketAddress bindAddress;
    private int bossThreads = 1;
    private int workerThreads;
    private int httpMaxContentLength = 65_536;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean shutdownEventLoops = true;
    private Class<? extends ServerSocketChannel> serverChannelClass = NioServerSocketChannel.class;
    private Consumer<ChannelPipeline> beforeProtocol = ignored -> { };
    private Consumer<ChannelPipeline> applicationPipeline = ignored -> { };
    private final List<Consumer<ServerBootstrap>> bootstrapConfigurers = new ArrayList<>();

    private NettyServerBuilder(Preset preset, IConnectionHandler connectionHandler,
                               WebSocketServerProtocolConfig webSocketProtocol,
                               ChannelInitializer<SocketChannel> customInitializer) {
        this.preset = preset;
        this.connectionHandler = connectionHandler;
        this.webSocketProtocol = webSocketProtocol;
        this.customInitializer = customInitializer;
    }

    public static NettyServerBuilder tcp(IConnectionHandler handler) {
        return new NettyServerBuilder(Preset.TCP, Objects.requireNonNull(handler, "handler"), null, null);
    }

    public static NettyServerBuilder webSocket(WebSocketServerProtocolConfig protocol,
                                                IConnectionHandler handler) {
        return new NettyServerBuilder(Preset.WEBSOCKET, Objects.requireNonNull(handler, "handler"),
                Objects.requireNonNull(protocol, "protocol"), null);
    }

    public static NettyServerBuilder custom(ChannelInitializer<SocketChannel> initializer) {
        return new NettyServerBuilder(Preset.CUSTOM, null, null,
                Objects.requireNonNull(initializer, "initializer"));
    }

    public NettyServerBuilder bind(String host, int port) {
        return bind(new InetSocketAddress(normalizeHost(host), validatePort(port)));
    }

    public NettyServerBuilder bind(int port) {
        return bind("0.0.0.0", port);
    }

    public NettyServerBuilder bind(SocketAddress address) {
        this.bindAddress = Objects.requireNonNull(address, "address");
        return this;
    }

    public NettyServerBuilder eventLoopThreads(int bossThreads, int workerThreads) {
        if (bossThreads <= 0) {
            throw new IllegalArgumentException("bossThreads must be positive");
        }
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must be non-negative");
        }
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
        return this;
    }

    /** Uses caller-provided event loops. Set shutdownOnStop only when this server owns them. */
    public NettyServerBuilder eventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                              boolean shutdownOnStop) {
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.shutdownEventLoops = shutdownOnStop;
        return this;
    }

    public NettyServerBuilder serverChannel(Class<? extends ServerSocketChannel> channelClass) {
        this.serverChannelClass = Objects.requireNonNull(channelClass, "channelClass");
        return this;
    }

    public NettyServerBuilder bootstrap(Consumer<ServerBootstrap> configurer) {
        bootstrapConfigurers.add(Objects.requireNonNull(configurer, "configurer"));
        return this;
    }

    /** Adds handlers before the built-in HTTP/WebSocket protocol handlers (for example TLS). */
    public NettyServerBuilder beforeProtocol(Consumer<ChannelPipeline> configurer) {
        beforeProtocol = beforeProtocol.andThen(Objects.requireNonNull(configurer, "configurer"));
        return this;
    }

    /** Adds application codecs immediately before the connection event dispatcher. */
    public NettyServerBuilder pipeline(Consumer<ChannelPipeline> configurer) {
        applicationPipeline = applicationPipeline.andThen(Objects.requireNonNull(configurer, "configurer"));
        return this;
    }

    public NettyServerBuilder httpMaxContentLength(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        this.httpMaxContentLength = maxContentLength;
        return this;
    }

    /** Replaces the preset pipeline completely while retaining server lifecycle and channel tracking. */
    public NettyServerBuilder initializer(ChannelInitializer<SocketChannel> initializer) {
        this.customInitializer = Objects.requireNonNull(initializer, "initializer");
        return this;
    }

    public NettyServer build() {
        if (bindAddress == null) {
            throw new IllegalStateException("bind address is required");
        }
        boolean createdGroups = bossGroup == null;
        EventLoopGroup actualBoss = bossGroup;
        EventLoopGroup actualWorker = workerGroup;
        try {
            if (createdGroups) {
                actualBoss = new MultiThreadIoEventLoopGroup(bossThreads, NioIoHandler.newFactory());
                actualWorker = new MultiThreadIoEventLoopGroup(workerThreads, NioIoHandler.newFactory());
            }
            ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
            ChannelInitializer<SocketChannel> initializer = trackedInitializer(channels, resolveInitializer());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(actualBoss, actualWorker)
                    .channel(serverChannelClass)
                    .childHandler(initializer);
            bootstrapConfigurers.forEach(configurer -> configurer.accept(bootstrap));
            return new NettyServer(bootstrap, bindAddress, channels, actualBoss, actualWorker,
                    createdGroups || shutdownEventLoops);
        } catch (RuntimeException | Error failure) {
            if (createdGroups || shutdownEventLoops) {
                shutdown(actualWorker);
                if (actualBoss != actualWorker) {
                    shutdown(actualBoss);
                }
            }
            throw failure;
        }
    }

    private ChannelInitializer<SocketChannel> resolveInitializer() {
        if (customInitializer != null) {
            return customInitializer;
        }
        return switch (preset) {
            case TCP -> tcpInitializer();
            case WEBSOCKET -> webSocketInitializer();
            case CUSTOM -> throw new IllegalStateException("custom initializer is required");
        };
    }

    private ChannelInitializer<SocketChannel> tcpInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                beforeProtocol.accept(pipeline);
                applicationPipeline.accept(pipeline);
                pipeline.addLast("connectionDispatcher", new NettyConnectionChannelHandler(connectionHandler));
            }
        };
    }

    private ChannelInitializer<SocketChannel> webSocketInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                beforeProtocol.accept(pipeline);
                pipeline.addLast("httpCodec", new HttpServerCodec());
                pipeline.addLast("httpAggregator", new HttpObjectAggregator(httpMaxContentLength));
                pipeline.addLast("webSocketProtocol", new WebSocketServerProtocolHandler(webSocketProtocol));
                pipeline.addLast("webSocketFrameDecoder", new WebSocketFrameToPacketDecoder());
                pipeline.addLast("webSocketFrameEncoder", new PacketToWebSocketFrameEncoder());
                applicationPipeline.accept(pipeline);
                pipeline.addLast("connectionDispatcher", new WebsocketConnectionChannelHandler(connectionHandler));
            }
        };
    }

    private static ChannelInitializer<SocketChannel> trackedInitializer(
            ChannelGroup channels, ChannelInitializer<SocketChannel> delegate) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                channels.add(channel);
                channel.pipeline().addLast(delegate);
            }
        };
    }

    private static int validatePort(int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        return port;
    }

    private static String normalizeHost(String host) {
        return host == null || host.isBlank() ? "0.0.0.0" : host;
    }

    private static void shutdown(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }
}
