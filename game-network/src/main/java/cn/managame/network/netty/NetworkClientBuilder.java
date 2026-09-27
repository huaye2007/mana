package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import java.util.*;
import java.util.function.Consumer;

public final class NetworkClientBuilder {
    public static final int DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE = NetworkSupport.MAX_MESSAGE_SIZE;
    ConnectionHandler handler;
    final List<Consumer<ChannelPipeline>> pipelines = new ArrayList<>();
    boolean webSocket;
    int maxMessageSize = DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE;
    SslContext ssl;
    EventLoopGroup group;
    ChannelFactory<? extends Channel> factory = NioSocketChannel::new;
    final Map<ChannelOption<?>, Object> options = new LinkedHashMap<>();

    NetworkClientBuilder() {}
    public NetworkClientBuilder handler(ConnectionHandler handler) { this.handler = Objects.requireNonNull(handler); return this; }
    public NetworkClientBuilder pipeline(Consumer<ChannelPipeline> configurer) { pipelines.add(Objects.requireNonNull(configurer)); return this; }
    public NetworkClientBuilder webSocket() { return webSocket(DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE); }
    public NetworkClientBuilder webSocket(int maxMessageSize) {
        this.maxMessageSize = NetworkSupport.messageSize(maxMessageSize); webSocket = true; return this;
    }
    public NetworkClientBuilder sslContext(SslContext ssl) {
        if (!Objects.requireNonNull(ssl).isClient()) throw new IllegalArgumentException("Client requires a client SslContext");
        this.ssl = ssl; return this;
    }
    public NetworkClientBuilder eventLoopGroup(EventLoopGroup group) { this.group = Objects.requireNonNull(group); return this; }
    public NetworkClientBuilder channelFactory(ChannelFactory<? extends Channel> factory) { this.factory = Objects.requireNonNull(factory); return this; }
    public <T> NetworkClientBuilder option(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value)); options.put(option, value); return this;
    }
    public NetworkClient build() {
        if (handler == null) throw new IllegalStateException("handler is required");
        return new NetworkClient(this);
    }
}