package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class NetworkServerBuilder {
    public static final int DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE = NetworkSupport.MAX_MESSAGE_SIZE;
    SocketAddress address;
    ConnectionHandler handler;
    final List<Consumer<ChannelPipeline>> pipelines = new ArrayList<>();
    String path;
    int maxMessageSize = DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE;
    EventLoopGroup boss, worker;
    /** Null selects the listener type matching the boss group (Epoll or NIO). */
    ChannelFactory<? extends ServerChannel> factory;
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
    /** Overrides the listener channel type; by default it matches the boss group's transport. */
    public NetworkServerBuilder channelFactory(ChannelFactory<? extends ServerChannel> factory) {
        this.factory = Objects.requireNonNull(factory);
        return this;
    }
    public <T> NetworkServerBuilder option(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value));
        options.put(option, value);
        return this;
    }
    public <T> NetworkServerBuilder childOption(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value));
        childOptions.put(option, value);
        return this;
    }
    public NetworkServer build() {
        if (address == null || handler == null) throw new IllegalStateException("bindAddress and handler are required");
        return new NetworkServer(this);
    }
}
