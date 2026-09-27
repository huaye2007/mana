package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connector.*;
import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.*;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;

/** Reusable connection factory; retains only unfinished connection attempts. */
public final class NetworkClient implements AutoCloseable {
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final boolean webSocket, ownGroup;
    private final int maxMessageSize;
    private final SslContext ssl;
    private SslContext defaultSsl;
    private final EventLoopGroup group;
    private final ChannelFactory<? extends Channel> factory;
    private final Map<ChannelOption<?>, Object> options;
    private final Set<ConnectAttempt> pending = new HashSet<>();
    private volatile boolean closed;

    NetworkClient(NetworkClientBuilder b) {
        handler = b.handler; pipelines = List.copyOf(b.pipelines); webSocket = b.webSocket;
        maxMessageSize = b.maxMessageSize; ssl = b.ssl; factory = b.factory; options = Map.copyOf(b.options);
        ownGroup = b.group == null; group = ownGroup ? new NioEventLoopGroup() : b.group;
    }
    public static NetworkClientBuilder builder() { return new NetworkClientBuilder(); }
    public Connection connect(SocketAddress address) {
        NetworkSupport.checkNotEventLoop(group);
        validate(address);
        return connect(address, null, null, null).await();
    }
    public void connectAsync(SocketAddress address, ConnectCallback callback) {
        Objects.requireNonNull(callback, "callback");
        try { validate(address); }
        catch (RuntimeException cause) { reject(callback, cause); return; }
        connect(address, null, null, callback);
    }
    public Connection connect(URI uri) { return connect(uri, WebSocketConnectOptions.defaults()); }
    public Connection connect(URI uri, WebSocketConnectOptions options) {
        NetworkSupport.checkNotEventLoop(group);
        SocketAddress address = validate(uri, options);
        return connect(address, uri, options, null).await();
    }
    public void connectAsync(URI uri, ConnectCallback callback) {
        connectAsync(uri, WebSocketConnectOptions.defaults(), callback);
    }
    public void connectAsync(URI uri, WebSocketConnectOptions options, ConnectCallback callback) {
        Objects.requireNonNull(callback, "callback");
        SocketAddress address;
        try { address = validate(uri, options); }
        catch (RuntimeException cause) { reject(callback, cause); return; }
        connect(address, uri, options, callback);
    }
    private void validate(SocketAddress address) {
        if (closed) throw new IllegalStateException("Client is closed");
        if (webSocket) throw new IllegalStateException("WebSocket client requires URI");
        Objects.requireNonNull(address, "remoteAddress");
        if (ssl != null && !(address instanceof InetSocketAddress))
            throw new IllegalArgumentException("TLS requires an InetSocketAddress for hostname verification");
    }
    private SocketAddress validate(URI uri, WebSocketConnectOptions options) {
        if (closed) throw new IllegalStateException("Client is closed");
        if (!webSocket) throw new IllegalStateException("TCP client requires SocketAddress");
        Objects.requireNonNull(uri, "uri"); Objects.requireNonNull(options, "options");
        String scheme = uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                || uri.getPort() > 65535 || uri.getPort() == 0)
            throw new IllegalArgumentException("Expected ws/wss URI with host and valid port, without user-info or fragment");
        int port = uri.getPort() < 0 ? ("wss".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
        return InetSocketAddress.createUnresolved(uri.getHost(), port);
    }
    private ConnectAttempt connect(SocketAddress address, URI uri, WebSocketConnectOptions wsOptions, ConnectCallback callback) {
        EventLoop loop = group.next();
        ConnectAttempt[] holder = new ConnectAttempt[1];
        ConnectAttempt attempt = new ConnectAttempt(loop, callback, () -> {
            synchronized (pending) { pending.remove(holder[0]); }
        });
        holder[0] = attempt;
        synchronized (pending) {
            if (!closed) pending.add(attempt);
            else { attempt.fail(new IllegalStateException("Client is closed")); return attempt; }
        }
        try {
            SslContext context = uri == null ? ssl : ("wss".equalsIgnoreCase(uri.getScheme()) ? clientSsl() : null);
            String host = address instanceof InetSocketAddress inet ? inet.getHostString() : null;
            int port = address instanceof InetSocketAddress inet ? inet.getPort() : -1;
            Bootstrap bootstrap = new Bootstrap().group(loop).channelFactory(factory);
            applyOptions(bootstrap);
            bootstrap.handler(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel ch) {
                    attempt.attach(ch);
                    try {
                        NetworkPipeline.install(ch, handler, pipelines, context, host, port,
                                webSocket, null, uri, wsOptions, maxMessageSize, attempt, () -> true, () -> {});
                    } catch (Throwable cause) { attempt.networkFailure(cause); }
                }
            });
            ChannelFuture future = bootstrap.connect(address);
            attempt.attach(future.channel());
            future.addListener(f -> { if (!f.isSuccess()) attempt.networkFailure(f.cause()); });
        } catch (Throwable cause) { attempt.networkFailure(cause); }
        return attempt;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyOptions(Bootstrap bootstrap) {
        options.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
    }
    private synchronized SslContext clientSsl() throws javax.net.ssl.SSLException {
        if (ssl != null) return ssl;
        if (defaultSsl == null) defaultSsl = SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();
        return defaultSsl;
    }
    private static void reject(ConnectCallback callback, Throwable cause) {
        try { callback.onFailure(cause); }
        catch (Throwable error) { NetworkSupport.log("ConnectCallback.onFailure failed", error); }
    }
    @Override public void close() {
        if (closed) return;
        NetworkSupport.checkNotEventLoop(group);
        List<ConnectAttempt> cancel;
        synchronized (pending) {
            if (closed) return;
            closed = true;
            cancel = List.copyOf(pending);
        }
        for (ConnectAttempt attempt : cancel) attempt.fail(new IllegalStateException("Client is closed"));
        if (ownGroup) NetworkSupport.shutdown(group);
        NetworkSupport.checkInterrupted("Client close");
    }
}