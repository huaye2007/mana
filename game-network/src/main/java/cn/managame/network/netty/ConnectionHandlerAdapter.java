package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.error.NetworkException;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.concurrent.Promise;
import java.nio.channels.ClosedChannelException;
import java.util.function.BooleanSupplier;

/** Direct protocol-completion and business callbacks on the channel EventLoop. */
final class ConnectionHandlerAdapter extends ChannelInboundHandlerAdapter {
    private final Channel channel;
    private final ConnectionHandler handler;
    private final BooleanSupplier open;
    private final Promise<Connection> result; // Client result only; null for an accepted server channel.
    private final ChannelFutureListener closedBeforeConnected;
    private SslHandler ssl;
    private Connection connection;
    private boolean initialized, active, webSocketPending, ended;

    ConnectionHandlerAdapter(Channel channel, ConnectionHandler handler,
                             BooleanSupplier open, Promise<Connection> result) {
        this.channel = channel;
        this.handler = handler;
        this.open = open;
        this.result = result;
        closedBeforeConnected = f -> failEstablishment(new ClosedChannelException());
        channel.closeFuture().addListener(closedBeforeConnected);
    }

    void initialized(SslHandler ssl, boolean webSocket) {
        this.ssl = ssl;
        webSocketPending = webSocket;
        initialized = true;
        tryConnected();
    }

    void failEstablishment(Throwable cause) {
        if (connection != null || ended) return;
        ended = true;
        channel.closeFuture().removeListener(closedBeforeConnected);
        channel.close();
        if (result != null) {
            result.tryFailure(!open.getAsBoolean() ? new IllegalStateException("Client is closed")
                    : cause instanceof NetworkException ? cause : new NetworkException("Connect failed", cause));
        } else if (open.getAsBoolean()) {
            NetworkSupport.logTransportFailure("Server transport establishment failed", channel, cause);
        }
    }

    private void tryConnected() {
        if (!initialized || !active || ended || connection != null) return;
        if (ssl != null && !ssl.handshakeFuture().isSuccess()) {
            if (ssl.handshakeFuture().isDone()) failEstablishment(ssl.handshakeFuture().cause());
            return;
        }
        if (webSocketPending) return;
        if (!open.getAsBoolean() || !channel.isActive()
                || result != null && (result.isDone() || !result.setUncancellable())) {
            failEstablishment(new ClosedChannelException());
            return;
        }
        channel.closeFuture().removeListener(closedBeforeConnected);
        Connection current = new NettyConnection(channel);
        connection = current;
        try { handler.onConnected(current); }
        catch (Throwable cause) { report(cause); }
        if (result != null) result.setSuccess(current);
    }

    @Override public void channelActive(ChannelHandlerContext ctx) {
        active = true;
        tryConnected();
        ctx.fireChannelActive();
    }

    boolean hasConnection() { return connection != null; }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (!ended) {
            if (connection == null) failEstablishment(new ClosedChannelException());
            else {
                ended = true;
                try { handler.onDisconnected(connection); }
                catch (Throwable cause) { report(cause); }
                finally { connection = null; }
            }
        }
        ctx.fireChannelInactive();
    }

    private void report(Throwable cause) {
        if (connection != null) {
            try { handler.onException(connection, cause); }
            catch (Throwable error) { NetworkSupport.log("ConnectionHandler.onException failed", error); }
        } else if (!ended) {
            failEstablishment(cause);
        } else {
            NetworkSupport.logTransportFailure("Exception outside connection lifecycle", channel, cause);
        }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        try {
            if (connection != null && !ended) handler.onMessage(connection, message);
            else if (!ended) failEstablishment(new IllegalStateException("Message before establishment"));
        } catch (Throwable cause) { report(cause); }
        finally { ReferenceCountUtil.release(message); }
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof SslHandshakeCompletionEvent tls) {
            if (tls.isSuccess()) tryConnected();
            else failEstablishment(tls.cause());
            return;
        }
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            webSocketPending = false;
            tryConnected();
            return;
        }
        if (event instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                || event instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent
                || connection == null || ended) return;
        try { handler.onEvent(connection, event); }
        catch (Throwable cause) { report(cause); }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        report(cause);
    }
}
