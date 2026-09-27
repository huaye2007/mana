package cn.managame.network.netty;

import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.function.BooleanSupplier;

final class TransportGate extends ChannelInboundHandlerAdapter {
    private final boolean tls, webSocket;
    private final ConnectionHandlerAdapter adapter;
    private ConnectAttempt attempt;
    private BooleanSupplier admit;
    private Runnable established;
    private boolean tlsComplete, ready;
    private io.netty.util.concurrent.ScheduledFuture<?> timeout;

    TransportGate(boolean tls, boolean webSocket, ConnectionHandlerAdapter adapter,
                  ConnectAttempt attempt, BooleanSupplier admit, Runnable established) {
        this.tls = tls; this.webSocket = webSocket; this.adapter = adapter;
        this.attempt = attempt; this.admit = admit; this.established = established;
    }
    @Override public void channelActive(ChannelHandlerContext ctx) {
        if (!tls && !webSocket) ready(ctx);
        // Netty server WS timeout starts only after an Upgrade request. Bound a silent peer too.
        if (webSocket && attempt == null && !ready) {
            timeout = ctx.executor().schedule(() -> failure(ctx,
                    new WebSocketHandshakeException("WebSocket establishment timed out")),
                    10, java.util.concurrent.TimeUnit.SECONDS);
        }
        ctx.fireChannelActive();
    }
    private void ready(ChannelHandlerContext ctx) {
        if (ready || !ctx.channel().isActive()) return;
        if (!admit.getAsBoolean() || (attempt != null && !attempt.claimSuccess())) {
            ctx.close(); return;
        }
        ready = true;
        cancelTimeout();
        releaseEstablishment();
        ConnectAttempt completedAttempt = attempt;
        attempt = null;
        admit = null;
        var connection = adapter.connected(ctx.channel());
        if (completedAttempt != null) completedAttempt.success(connection);
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof SslHandshakeCompletionEvent ssl && tls) {
            if (!ssl.isSuccess()) failure(ctx, ssl.cause());
            else { tlsComplete = true; if (!webSocket) ready(ctx); }
            return;
        }
        if (webSocket && (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE)) {
            if (!tls || tlsComplete) ready(ctx);
            else failure(ctx, new IllegalStateException("WebSocket completed before TLS"));
            return;
        }
        if (webSocket && (event instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                || event instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent)) {
            if (event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT
                    || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT)
                failure(ctx, new WebSocketHandshakeException("WebSocket handshake timed out"));
            return;
        }
        if (ready) ctx.fireUserEventTriggered(event);
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!ready) {
            ReferenceCountUtil.release(message);
            failure(ctx, new IllegalStateException("Message arrived before transport establishment"));
        } else ctx.fireChannelRead(message);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) {
        cancelTimeout();
        releaseEstablishment();
        if (!ready && attempt != null) attempt.networkFailure(new ClosedChannelException());
        adapter.disconnected();
        ctx.fireChannelInactive();
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ready) failure(ctx, cause);
        else if (webSocket && (cause instanceof TooLongFrameException
                || cause instanceof CorruptedWebSocketFrameException)) {
            // Invalid transport messages never reach application message handling.
            ctx.close();
        } else ctx.fireExceptionCaught(cause);
    }
    private void failure(ChannelHandlerContext ctx, Throwable cause) {
        cancelTimeout();
        if (attempt != null) attempt.networkFailure(cause);
        else NetworkSupport.log("Transport establishment failed", cause);
        ctx.close();
    }
    private void releaseEstablishment() {
        if (established != null) {
            established.run();
            established = null;
        }
    }
    private void cancelTimeout() {
        if (timeout != null) { timeout.cancel(false); timeout = null; }
    }
}