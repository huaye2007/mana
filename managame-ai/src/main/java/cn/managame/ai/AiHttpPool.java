package cn.managame.ai;

import cn.managame.network.netty.NetworkTransports;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.BootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/**
 * Lightweight Netty HTTP/1.1 keep-alive connection pool for {@link AiClient}. Reuses warm connections in LIFO order,
 * supports HTTPS with hostname verification, and never returns aborted or non-keep-alive channels to the pool.
 */
final class AiHttpPool implements AutoCloseable {
    interface ExchangeListener {
        void onConnectFuture(ChannelFuture future);
        boolean onChannelAssigned(Channel channel);
        boolean onResponseHeaders(int status, HttpHeaders headers, Channel channel);
        boolean onContent(ByteBuf content, boolean last, Channel channel);
        void onComplete();
        void onFailure(Throwable cause);
    }

    private final Bootstrap bootstrap;
    private final EventLoopGroup ownedGroup;
    private final InetSocketAddress remote;
    private final SslContext ssl;
    private final String host;
    private final int port;
    private final ChannelGroup allChannels = new DefaultChannelGroup("ai-client", GlobalEventExecutor.INSTANCE);
    private final ConcurrentLinkedDeque<Channel> idle = new ConcurrentLinkedDeque<>();
    private volatile boolean closed;

    AiHttpPool(Bootstrap baseBootstrap, String scheme, String host, int port, SslContext explicitSsl, int connectTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.remote = new InetSocketAddress(host, port);
        if ("https".equalsIgnoreCase(scheme)) {
            if (explicitSsl != null) {
                if (!explicitSsl.isClient()) throw new IllegalArgumentException("Client SslContext required");
                this.ssl = explicitSsl;
            } else {
                try {
                    this.ssl = SslContextBuilder.forClient().build();
                } catch (SSLException e) {
                    throw new IllegalStateException("Cannot initialize default HTTPS client SslContext", e);
                }
            }
        } else {
            this.ssl = null;
        }
        Bootstrap completed = baseBootstrap != null ? baseBootstrap.clone() : new Bootstrap();
        BootstrapConfig cfg = completed.config();
        if (cfg.group() == null) {
            this.ownedGroup = NetworkTransports.newEventLoopGroup(0, "ai-io");
            completed.group(this.ownedGroup);
        } else {
            this.ownedGroup = null;
        }
        if (completed.config().channelFactory() == null) {
            completed.channelFactory(NetworkTransports.socketChannelFactory(completed.config().group()));
        }
        if (!cfg.options().containsKey(ChannelOption.TCP_NODELAY)) {
            completed.option(ChannelOption.TCP_NODELAY, true);
        }
        if (!cfg.options().containsKey(ChannelOption.CONNECT_TIMEOUT_MILLIS)) {
            completed.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        }
        completed.handler(new ChannelInitializer<>() {
            @Override protected void initChannel(Channel ch) {
                if (ssl != null) {
                    SslHandler sslHandler = ssl.newHandler(ch.alloc(), AiHttpPool.this.host, AiHttpPool.this.port);
                    SSLParameters params = sslHandler.engine().getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    sslHandler.engine().setSSLParameters(params);
                    ch.pipeline().addLast("ssl", sslHandler);
                }
                ch.pipeline().addLast("http", new HttpClientCodec());
                ch.pipeline().addLast("exchange", new PoolChannelHandler());
            }
        });
        this.bootstrap = completed;
    }

    void send(FullHttpRequest request, ExchangeListener listener) {
        if (closed) {
            ReferenceCountUtil.safeRelease(request);
            listener.onFailure(new ClosedChannelException());
            return;
        }
        Channel ch;
        while ((ch = idle.pollFirst()) != null) {
            if (ch.isActive()) {
                final Channel picked = ch;
                if (picked.eventLoop().inEventLoop()) {
                    writeOnPooled(picked, request, listener);
                } else {
                    picked.eventLoop().execute(() -> writeOnPooled(picked, request, listener));
                }
                return;
            }
        }
        dialAndWrite(request, listener);
    }

    private void writeOnPooled(Channel ch, FullHttpRequest request, ExchangeListener listener) {
        if (closed) {
            ReferenceCountUtil.safeRelease(request);
            ch.close();
            listener.onFailure(new ClosedChannelException());
            return;
        }
        PoolChannelHandler handler = ch.pipeline().get(PoolChannelHandler.class);
        if (!ch.isActive() || handler == null || !handler.bind(listener)) {
            dialAndWrite(request, listener);
            return;
        }
        if (!listener.onChannelAssigned(ch)) {
            ReferenceCountUtil.safeRelease(request);
            handler.unbind(listener);
            offerIdle(ch);
            return;
        }
        ch.config().setAutoRead(true);
        ch.writeAndFlush(request).addListener((ChannelFuture wf) -> {
            if (!wf.isSuccess()) {
                handler.failIfCurrent(listener, wf.cause());
                ch.close();
            }
        });
    }

    private void dialAndWrite(FullHttpRequest request, ExchangeListener listener) {
        if (closed) {
            ReferenceCountUtil.safeRelease(request);
            listener.onFailure(new ClosedChannelException());
            return;
        }
        ChannelFuture cf = bootstrap.connect(remote);
        listener.onConnectFuture(cf);
        cf.addListener((ChannelFuture connected) -> {
            if (!connected.isSuccess()) {
                ReferenceCountUtil.safeRelease(request);
                listener.onFailure(connected.cause());
                return;
            }
            Channel ch = connected.channel();
            allChannels.add(ch);
            if (closed) {
                ReferenceCountUtil.safeRelease(request);
                ch.close();
                listener.onFailure(new ClosedChannelException());
                return;
            }
            PoolChannelHandler handler = ch.pipeline().get(PoolChannelHandler.class);
            if (!ch.isActive() || handler == null || !handler.bind(listener)) {
                ReferenceCountUtil.safeRelease(request);
                ch.close();
                listener.onFailure(new ClosedChannelException());
                return;
            }
            if (!listener.onChannelAssigned(ch)) {
                ReferenceCountUtil.safeRelease(request);
                handler.unbind(listener);
                ch.close();
                return;
            }
            ch.config().setAutoRead(true);
            ch.writeAndFlush(request).addListener((ChannelFuture wf) -> {
                if (!wf.isSuccess()) {
                    handler.failIfCurrent(listener, wf.cause());
                    ch.close();
                }
            });
        });
    }

    private void offerIdle(Channel ch) {
        if (!closed && ch.isActive()) {
            ch.config().setAutoRead(true);
            idle.offerFirst(ch);
            if (closed && idle.remove(ch)) {
                ch.close();
            }
        } else {
            ch.close();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Channel ch;
        while ((ch = idle.pollFirst()) != null) ch.close();
        boolean onOwnedLoop = false;
        if (ownedGroup != null) {
            for (EventExecutor exec : ownedGroup) {
                if (exec.inEventLoop()) {
                    onOwnedLoop = true;
                    break;
                }
            }
        }
        if (!onOwnedLoop) {
            allChannels.close().awaitUninterruptibly();
        } else {
            allChannels.close();
        }
        if (ownedGroup != null) {
            Future<?> shutdown = ownedGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            if (!onOwnedLoop) shutdown.awaitUninterruptibly();
        }
    }

    private final class PoolChannelHandler extends ChannelInboundHandlerAdapter {
        private ExchangeListener current;
        private boolean keepAlive;

        boolean bind(ExchangeListener listener) {
            if (current != null) return false;
            current = listener;
            keepAlive = false;
            return true;
        }

        void unbind(ExchangeListener listener) {
            if (current == listener) {
                current = null;
                keepAlive = false;
            }
        }

        void failIfCurrent(ExchangeListener listener, Throwable cause) {
            if (current == listener) {
                current = null;
                keepAlive = false;
                listener.onFailure(cause);
            }
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof HttpObject httpObj)) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (httpObj.decoderResult().isFailure()) {
                ReferenceCountUtil.release(msg);
                ExchangeListener active = current;
                current = null;
                ctx.close();
                if (active != null) active.onFailure(new IOException("Malformed HTTP response", httpObj.decoderResult().cause()));
                return;
            }
            if (msg instanceof HttpResponse res) {
                int code = res.status().code();
                if (code >= 100 && code < 200 && code != 101) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
                ExchangeListener active = current;
                if (active == null) {
                    ReferenceCountUtil.release(msg);
                    ctx.close();
                    return;
                }
                keepAlive = HttpUtil.isKeepAlive(res);
                if (!active.onResponseHeaders(code, res.headers(), ctx.channel())) {
                    current = null;
                    ReferenceCountUtil.release(msg);
                    ctx.close();
                    return;
                }
            }
            if (msg instanceof HttpContent content) {
                try {
                    ExchangeListener active = current;
                    if (active == null) return;
                    boolean last = content instanceof LastHttpContent;
                    if (!active.onContent(content.content(), last, ctx.channel())) {
                        current = null;
                        ctx.close();
                        return;
                    }
                    if (last && current == active) {
                        current = null;
                        boolean reuse = keepAlive;
                        keepAlive = false;
                        if (reuse) {
                            offerIdle(ctx.channel());
                        } else {
                            ctx.close();
                        }
                        active.onComplete();
                    }
                } finally {
                    content.release();
                }
            }
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            idle.remove(ctx.channel());
            ExchangeListener active = current;
            current = null;
            if (active != null) {
                active.onFailure(new ClosedChannelException());
            }
            ctx.fireChannelInactive();
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            idle.remove(ctx.channel());
            ExchangeListener active = current;
            current = null;
            ctx.close();
            if (active != null) {
                active.onFailure(cause);
            }
        }
    }
}
