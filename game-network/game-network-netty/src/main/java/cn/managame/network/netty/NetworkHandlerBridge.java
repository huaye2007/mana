package cn.managame.network.netty;

import cn.managame.network.Connection;
import cn.managame.network.ConnectionType;
import cn.managame.network.IdleType;
import cn.managame.network.NetworkHandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.Promise;

import java.nio.channels.ClosedChannelException;

/** Native pipeline handler mapping Netty callbacks to the user NetworkHandler. */
final class NetworkHandlerBridge extends SimpleChannelInboundHandler<Object> {
    private final NettyConnection connection;
    private final NetworkHandler handler;
    private final Promise<Connection> readiness;

    NetworkHandlerBridge(
            NettyConnection connection, NetworkHandler handler, Promise<Connection> readiness) {
        this.connection = connection;
        this.handler = handler;
        this.readiness = readiness;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (connection.type() == ConnectionType.TCP) connected();
        ctx.fireChannelActive();
    }

    private void connected() {
        if (readiness.isDone()) return;
        try {
            handler.onConnected(connection);
        } catch (Throwable failure) {
            readiness.tryFailure(failure);
            connection.close();
            return;
        }
        readiness.trySuccess(connection);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object message) throws Exception {
        if (readiness.isSuccess()) handler.onMessage(connection, message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            if (readiness.isSuccess()) handler.onDisconnected(connection);
            else readiness.tryFailure(new ClosedChannelException());
        } finally {
            ctx.fireChannelInactive();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || event
                        == WebSocketClientProtocolHandler.ClientHandshakeStateEvent
                                .HANDSHAKE_COMPLETE) {
            connected();
        } else if (event
                        == WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                                .HANDSHAKE_TIMEOUT
                || event
                        == WebSocketClientProtocolHandler.ClientHandshakeStateEvent
                                .HANDSHAKE_TIMEOUT) {
            readiness.tryFailure(
                    new java.util.concurrent.TimeoutException("WebSocket handshake timed out"));
        } else if (event instanceof IdleStateEvent idle && readiness.isSuccess()) {
            handler.onIdle(
                    connection,
                    switch (idle.state()) {
                        case READER_IDLE -> IdleType.READ;
                        case WRITER_IDLE -> IdleType.WRITE;
                        case ALL_IDLE -> IdleType.ALL;
                    });
        }
        ctx.fireUserEventTriggered(event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (!readiness.isDone()) readiness.tryFailure(cause);
        else handler.onException(connection, cause);
    }
}
