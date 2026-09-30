package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connector.*;
import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import java.util.concurrent.RejectedExecutionException;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connection factory and EventLoop ownership; no connection or attempt registry. */
public final class NetworkClient implements AutoCloseable {
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final boolean webSocket, ownGroup;
    private final int maxMessageSize;
    private final EventLoopGroup group;
    private final ChannelFactory<? extends Channel> factory;
    private final Map<ChannelOption<?>, Object> options;
    private final AtomicBoolean closed = new AtomicBoolean();

    NetworkClient(NetworkClientBuilder b) {
        handler = b.handler; pipelines = List.copyOf(b.pipelines); webSocket = b.webSocket;
        maxMessageSize = b.maxMessageSize; factory = b.factory; options = Map.copyOf(b.options);
        ownGroup = b.group == null; group = ownGroup ? new NioEventLoopGroup() : b.group;
    }
    public static NetworkClientBuilder builder() { return new NetworkClientBuilder(); }
    public Connection connect(SocketAddress address) {
        NetworkSupport.checkNotEventLoop(group);
        validate(address);
        return await(connect(address, null, null, null));
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
        return await(connect(address, uri, options, null));
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
        if (closed.get()) throw new IllegalStateException("Client is closed");
        if (webSocket) throw new IllegalStateException("WebSocket client requires URI");
        Objects.requireNonNull(address, "remoteAddress");
    }
    private SocketAddress validate(URI uri, WebSocketConnectOptions options) {
        if (closed.get()) throw new IllegalStateException("Client is closed");
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
    private Future<Connection> connect(SocketAddress address, URI uri, WebSocketConnectOptions wsOptions, ConnectCallback callback) {
        EventLoop loop = group.next();
        // Cleanup and notification must still run if the selected EventLoop rejects execution.
        Promise<Connection> result = ImmediateEventExecutor.INSTANCE.newPromise();
        Runnable connect = () -> {
            startConnect(loop, address, uri, wsOptions, result);
            // Register after channel cleanup so failure notification cannot delay reclamation.
            if (callback != null) result.addListener(f -> notifyCallback(loop, callback, result));
        };
        try {
            if (loop.inEventLoop()) connect.run();
            else loop.execute(connect);
        } catch (RejectedExecutionException cause) {
            result.tryFailure(networkFailure(cause));
            if (callback != null) reject(callback, result.cause());
        }
        return result;
    }

    private void startConnect(EventLoop loop, SocketAddress address, URI uri,
                              WebSocketConnectOptions wsOptions, Promise<Connection> result) {
        if (result.isDone()) return;
        if (closed.get()) {
            result.tryFailure(new IllegalStateException("Client is closed"));
            return;
        }
        try {
            Bootstrap bootstrap = new Bootstrap().group(loop).channelFactory(factory);
            applyOptions(bootstrap);
            WebSocketTransport protocol = webSocket ? WebSocketTransport.client(uri, wsOptions, maxMessageSize) : null;
            bootstrap.handler(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    if (result.isDone()) { channel.close(); return; }
                    ConnectionHandlerAdapter adapter = new ConnectionHandlerAdapter(
                            channel, handler, () -> !closed.get(), result);
                    NetworkChannelInitializer.configure(channel, pipelines, protocol, adapter);
                }
            });
            ChannelFuture future = bootstrap.connect(address);
            // A completed cancellation also closes a channel created after the waiter exited.
            result.addListener(f -> { if (!f.isSuccess()) future.channel().close(); });
            future.addListener(f -> { if (!f.isSuccess()) result.tryFailure(networkFailure(f.cause())); });
        } catch (Throwable cause) { result.tryFailure(networkFailure(cause)); }
    }

    private static Connection await(Future<Connection> result) {
        try { result.await(); }
        catch (InterruptedException interrupted) {
            result.cancel(false);
            // Success may already be claimed while onConnected is still executing.
            result.addListener(f -> { if (f.isSuccess()) result.getNow().close(); });
            Thread.currentThread().interrupt();
            throw new NetworkException("Connect interrupted", interrupted);
        }
        if (result.isSuccess()) return result.getNow();
        Throwable cause = result.cause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw networkFailure(cause);
    }

    private static NetworkException networkFailure(Throwable cause) {
        return cause instanceof NetworkException error ? error : new NetworkException("Connect failed", cause);
    }

    private static void notifyCallback(EventLoop loop, ConnectCallback callback, Future<Connection> result) {
        Runnable notify = () -> {
            try {
                if (result.isSuccess()) callback.onSuccess(result.getNow());
                else callback.onFailure(result.cause());
            } catch (Throwable cause) { NetworkSupport.log("ConnectCallback failed", cause); }
        };
        if (loop.inEventLoop()) notify.run();
        else {
            try { loop.execute(notify); }
            catch (RejectedExecutionException rejected) { notify.run(); }
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyOptions(Bootstrap bootstrap) {
        options.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
    }
    private static void reject(ConnectCallback callback, Throwable cause) {
        try { callback.onFailure(cause); }
        catch (Throwable error) { NetworkSupport.log("ConnectCallback.onFailure failed", error); }
    }
    @Override public void close() {
        if (closed.get()) return;
        NetworkSupport.checkNotEventLoop(group);
        if (!closed.compareAndSet(false, true)) return;
        if (ownGroup) NetworkSupport.shutdown(group);
        NetworkSupport.checkInterrupted("Client close");
    }
}
