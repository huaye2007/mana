package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.SocketAddress;
import java.util.*;
import java.util.function.Consumer;

public final class NetworkServerBuilder {
    public static final int DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE = NetworkSupport.MAX_MESSAGE_SIZE;
    SocketAddress address;
    ConnectionHandler handler;
    final List<Consumer<ChannelPipeline>> pipelines = new ArrayList<>();
    String path;
    int maxMessageSize = DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE;
    EventLoopGroup boss, worker;
    ChannelFactory<? extends ServerChannel> factory = NioServerSocketChannel::new;
    final Map<ChannelOption<?>, Object> options = new LinkedHashMap<>(), childOptions = new LinkedHashMap<>();

    NetworkServerBuilder() {}
    public NetworkServerBuilder bindAddress(SocketAddress address) { this.address = Objects.requireNonNull(address); return this; }
    public NetworkServerBuilder handler(ConnectionHandler handler) { this.handler = Objects.requireNonNull(handler); return this; }
    public NetworkServerBuilder pipeline(Consumer<ChannelPipeline> configurer) { pipelines.add(Objects.requireNonNull(configurer)); return this; }
    public NetworkServerBuilder webSocket(String path) { return webSocket(path, DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE); }
    public NetworkServerBuilder webSocket(String path, int maxMessageSize) {
        this.path = NetworkSupport.path(path);
        this.maxMessageSize = NetworkSupport.messageSize(maxMessageSize);
        return this;
    }
    public NetworkServerBuilder bossGroup(EventLoopGroup group) { boss = Objects.requireNonNull(group); return this; }
    public NetworkServerBuilder workerGroup(EventLoopGroup group) { worker = Objects.requireNonNull(group); return this; }
    public NetworkServerBuilder channelFactory(ChannelFactory<? extends ServerChannel> factory) { this.factory = Objects.requireNonNull(factory); return this; }
    public <T> NetworkServerBuilder option(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value)); options.put(option, value); return this;
    }
    public <T> NetworkServerBuilder childOption(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value)); childOptions.put(option, value); return this;
    }
    public NetworkServer build() {
        if (address == null || handler == null) throw new IllegalStateException("bindAddress and handler are required");
        return new NetworkServer(this);
    }
}
