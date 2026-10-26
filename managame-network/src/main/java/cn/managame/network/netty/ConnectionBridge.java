package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import java.lang.System.Logger.Level;

/**
 * The terminal handler of every server pipeline: turns Netty events into ConnectionHandler callbacks. It keeps
 * no state; the ConnectionMapper creates the connection on the event that completes establishment and resolves
 * it for every later event, and a channel without a mapped connection gets no callbacks.
 *
 * <p>Establishment completes on channelActive for plain TCP, on the TLS handshake event with TLS, and on the
 * WebSocket handshake event with WebSocket, which always follows TLS. Both events travel the pipeline, so
 * handlers before this one see them first and can still reject the connection by closing it.
 */
@Sharable
final class ConnectionBridge extends ChannelInboundHandlerAdapter {
    private static final System.Logger LOG = System.getLogger("cn.managame.network");
    private final ConnectionHandler handler;
    private final ConnectionMapper mapper;

    ConnectionBridge(ConnectionHandler handler, ConnectionMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @Override public void channelActive(ChannelHandlerContext ctx) {
        if (ctx.pipeline().get(SslHandler.class) == null && !isWebSocket(ctx)) establish(ctx);
        ctx.fireChannelActive();
    }

    @SuppressWarnings("deprecation")
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof HandshakeComplete handshake) {
            ctx.channel().attr(NetworkAttributes.WEBSOCKET_HANDSHAKE).set(handshake);
            establish(ctx);
        } else if (event instanceof SslHandshakeCompletionEvent tls) {
            // A failed handshake is closed by SslHandler itself.
            if (tls.isSuccess() && !isWebSocket(ctx)) establish(ctx);
        } else if (!(event instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent)) {
            try {
                Connection connection = mapper.get(ctx.channel());
                if (connection != null) handler.onEvent(connection, event);
            } catch (Throwable cause) {
                report(ctx, cause);
            } finally {
                ReferenceCountUtil.release(event);
            }
        }
    }

    private void establish(ChannelHandlerContext ctx) {
        // A handler before this one may have closed the channel while reacting to the handshake event.
        if (!ctx.channel().isActive()) return;
        Connection connection;
        try {
            connection = mapper.create(ctx.channel());
            if (connection == null) throw new NullPointerException("ConnectionMapper.create returned null");
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "ConnectionMapper.create failed; peer=" + ctx.channel().remoteAddress(), cause);
            ctx.close();
            return;
        }
        try { handler.onActive(connection); }
        catch (Throwable cause) { report(ctx, cause); }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        try {
            Connection connection = mapper.get(ctx.channel());
            if (connection != null) handler.onRead(connection, message);
        } catch (Throwable cause) {
            report(ctx, cause);
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    /** Before establishment, or once unmapped, an exception closes the channel; a handshake failure ends here. */
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (mapper.get(ctx.channel()) != null) {
            report(ctx, cause);
            return;
        }
        if (LOG.isLoggable(Level.DEBUG))
            LOG.log(Level.DEBUG, "Closing unestablished connection; peer=" + ctx.channel().remoteAddress() + "; " + cause);
        ctx.close();
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        Connection connection = mapper.get(ctx.channel());
        if (connection != null) {
            try {
                handler.onInActive(connection);
            } catch (Throwable cause) {
                report(ctx, cause);
            } finally {
                try { mapper.remove(ctx.channel()); }
                catch (Throwable cause) { LOG.log(Level.ERROR, "ConnectionMapper.remove failed; peer=" + ctx.channel().remoteAddress(), cause); }
            }
        }
        ctx.fireChannelInactive();
    }

    /** To onException of the mapped connection; logged when there is none or onException itself fails. */
    private void report(ChannelHandlerContext ctx, Throwable cause) {
        try {
            Connection connection = mapper.get(ctx.channel());
            if (connection != null) {
                handler.onException(connection, cause);
                return;
            }
        } catch (Throwable error) {
            cause.addSuppressed(error);
        }
        LOG.log(Level.ERROR, "Unhandled connection exception; peer=" + ctx.channel().remoteAddress(), cause);
    }

    private static boolean isWebSocket(ChannelHandlerContext ctx) {
        return ctx.pipeline().get(WebSocketServerProtocolHandler.class) != null;
    }
}
