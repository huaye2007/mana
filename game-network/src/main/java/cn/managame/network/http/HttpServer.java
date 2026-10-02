package cn.managame.network.http;

import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Independent one-shot HTTP/1.1 listener. Externally supplied groups remain caller-owned. */
public final class HttpServer implements AutoCloseable {
    static final System.Logger LOG = System.getLogger("cn.managame.network.http");
    private final SocketAddress address;
    private final BiConsumer<FullHttpRequest, HttpResponseCallback> handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final EventExecutorGroup executor;
    private final ChannelFactory<? extends ServerChannel> factory;
    private final Map<ChannelOption<?>, Object> options, childOptions;
    private final int bodyLimit, lineLimit, headerLimit;
    private final long readTimeoutMillis;
    private final boolean ownBoss, ownWorker;
    private volatile EventLoopGroup boss, worker;
    private volatile Channel listener;
    private volatile boolean closed;
    private boolean started;

    HttpServer(HttpServerBuilder b) {
        address = b.address; handler = b.handler; pipelines = List.copyOf(b.pipelines);
        executor = b.executor; factory = b.factory;
        options = Map.copyOf(b.options); childOptions = Map.copyOf(b.childOptions);
        bodyLimit = b.maxContentLength; lineLimit = b.maxInitialLineLength; headerLimit = b.maxHeaderSize;
        readTimeoutMillis = b.readTimeoutMillis;
        boss = b.boss; worker = b.worker; ownBoss = boss == null; ownWorker = worker == null;
    }
    public static HttpServerBuilder builder() { return new HttpServerBuilder(); }

    public void start() {
        checkThread();
        synchronized (this) {
            if (started || closed) throw new IllegalStateException("HTTP server already started or closed");
            started = true;
            try {
                if (ownBoss) boss = new NioEventLoopGroup(1);
                if (ownWorker) worker = new NioEventLoopGroup();
                ServerBootstrap bootstrap = new ServerBootstrap().group(boss, worker).channelFactory(factory);
                applyOptions(bootstrap);
                bootstrap.childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        if (closed) { channel.close(); return; }
                        ChannelPipeline pipeline = channel.pipeline();
                        if (readTimeoutMillis != 0) pipeline.addLast("http-read-timeout",
                                new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
                        HttpServerTransport.configure(pipeline, executor, handler, bodyLimit, lineLimit, headerLimit, pipelines);
                        if (closed) { channel.close(); return; }
                        SslHandler ssl = pipeline.get(SslHandler.class);
                        if (ssl != null && (pipeline.first() != ssl || pipeline.toMap().values().stream()
                                .filter(SslHandler.class::isInstance).count() != 1))
                            throw new IllegalArgumentException("Configure exactly one SslHandler using addFirst");
                    }
                });
                ChannelFuture bind = bootstrap.bind(address);
                listener = bind.channel();
                bind.sync();
            } catch (Throwable cause) {
                closed = true;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                try { cleanup(); } catch (Throwable cleanup) { cause.addSuppressed(cleanup); }
                throw new NetworkException("HTTP bind failed: " + address, cause);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyOptions(ServerBootstrap bootstrap) {
        options.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
        childOptions.forEach((key, value) -> bootstrap.childOption((ChannelOption) key, value));
    }
    public SocketAddress localAddress() { return listener == null ? null : listener.localAddress(); }
    @Override public void close() {
        if (closed) return;
        checkThread();
        synchronized (this) {
            if (closed) return;
            closed = true;
            cleanup();
            if (Thread.currentThread().isInterrupted())
                throw new NetworkException("HTTP close interrupted", new InterruptedException("HTTP close"));
        }
    }
    private void checkThread() {
        for (EventExecutorGroup group : new EventExecutorGroup[] { boss, worker, executor })
            if (group != null) for (var eventExecutor : group)
                if (eventExecutor.inEventLoop())
                    throw new IllegalStateException("Blocking HTTP lifecycle operation on its executor");
    }
    private void cleanup() {
        try {
            if (listener != null) await(listener.close(), "HTTP listener close failed");
        } finally {
            try { if (ownBoss && boss != null) await(boss.shutdownGracefully(0, 5, TimeUnit.SECONDS), "HTTP boss shutdown failed"); }
            finally { if (ownWorker && worker != null && worker != boss)
                await(worker.shutdownGracefully(0, 5, TimeUnit.SECONDS), "HTTP worker shutdown failed"); }
        }
    }
    private static void await(Future<?> future, String message) {
        future.awaitUninterruptibly();
        if (!future.isSuccess()) throw new NetworkException(message, future.cause());
    }
    static void log(String message, Throwable cause) { LOG.log(System.Logger.Level.ERROR, message, cause); }
}
