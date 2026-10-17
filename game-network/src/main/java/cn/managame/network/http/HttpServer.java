package cn.managame.network.http;

import cn.managame.network.internal.ListenerLifecycle;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Independent one-shot HTTP/1.1 listener. Externally supplied groups remain caller-owned. */
public final class HttpServer implements AutoCloseable {
    static final System.Logger LOG = System.getLogger("cn.managame.network.http");
    private final BiConsumer<FullHttpRequest, HttpResponseCallback> handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final EventExecutorGroup executor;
    private final int bodyLimit, lineLimit, headerLimit;
    private final long readTimeoutMillis;
    private final ListenerLifecycle lifecycle;

    HttpServer(HttpServerBuilder b) {
        handler = b.handler;
        pipelines = List.copyOf(b.pipelines);
        executor = b.executor;
        bodyLimit = b.maxContentLength;
        lineLimit = b.maxInitialLineLength;
        headerLimit = b.maxHeaderSize;
        readTimeoutMillis = b.readTimeoutMillis;
        lifecycle = new ListenerLifecycle("HTTP server", b.address, b.factory, b.options, b.childOptions,
                b.boss, b.worker, b.executor);
    }

    public static HttpServerBuilder builder() { return new HttpServerBuilder(); }

    public void start() {
        lifecycle.start(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                if (lifecycle.isClosed()) { channel.close(); return; }
                ChannelPipeline pipeline = channel.pipeline();
                if (readTimeoutMillis != 0) pipeline.addLast("http-read-timeout",
                        new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
                HttpServerTransport.configure(pipeline, executor, handler, bodyLimit, lineLimit, headerLimit, pipelines);
                if (lifecycle.isClosed()) { channel.close(); return; }
                SslHandler ssl = pipeline.get(SslHandler.class);
                if (ssl != null && (pipeline.first() != ssl || pipeline.toMap().values().stream()
                        .filter(SslHandler.class::isInstance).count() != 1))
                    throw new IllegalArgumentException("Configure exactly one SslHandler using addFirst");
            }
        });
    }

    public SocketAddress localAddress() { return lifecycle.localAddress(); }

    @Override public void close() { lifecycle.close(); }

    static void log(String message, Throwable cause) { LOG.log(System.Logger.Level.ERROR, message, cause); }
}
