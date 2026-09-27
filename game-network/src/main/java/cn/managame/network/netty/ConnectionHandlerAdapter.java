package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.util.function.Consumer;

final class ConnectionHandlerAdapter extends ChannelInboundHandlerAdapter {
    private final ConnectionHandler handler;
    private Consumer<Throwable> establishmentFailure;
    private Connection connection;
    private boolean ended;

    ConnectionHandlerAdapter(ConnectionHandler handler, Consumer<Throwable> establishmentFailure) {
        this.handler = handler; this.establishmentFailure = establishmentFailure;
    }
    Connection connected(Channel channel) {
        if (connection != null || ended) return connection;
        Connection current = new NettyConnection(channel);
        connection = current;
        establishmentFailure = null;
        try { handler.onConnected(current); }
        catch (Throwable cause) { report(cause); }
        return current;
    }
    boolean hasConnection() { return connection != null; }
    void disconnected() {
        if (ended) return;
        ended = true;
        if (connection != null) {
            try { handler.onDisconnected(connection); }
            catch (Throwable cause) { report(cause); }
            finally { connection = null; }
        }
    }
    void report(Throwable cause) {
        if (connection == null) {
            if (!ended) establishmentFailure.accept(cause);
            else NetworkSupport.log("Exception after connection lifecycle", cause);
            return;
        }
        try { handler.onException(connection, cause); }
        catch (Throwable error) { NetworkSupport.log("ConnectionHandler.onException failed", error); }
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!ctx.channel().eventLoop().inEventLoop()) {
            try { ctx.channel().eventLoop().execute(() -> read(message)); }
            catch (java.util.concurrent.RejectedExecutionException rejected) { ReferenceCountUtil.release(message); }
        } else read(message);
    }
    private void read(Object message) {
        try {
            if (connection != null && !ended) handler.onMessage(connection, message);
            else if (!ended) establishmentFailure.accept(new IllegalStateException("Message before establishment"));
        } catch (Throwable cause) { report(cause); }
        finally { ReferenceCountUtil.release(message); }
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (!ctx.channel().eventLoop().inEventLoop()) {
            ctx.channel().eventLoop().execute(() -> event(event));
        } else event(event);
    }
    private void event(Object event) {
        if (connection == null || ended) return;
        try { handler.onEvent(connection, event); }
        catch (Throwable cause) { report(cause); }
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ctx.channel().eventLoop().inEventLoop()) {
            ctx.channel().eventLoop().execute(() -> report(cause));
        } else report(cause);
    }
}