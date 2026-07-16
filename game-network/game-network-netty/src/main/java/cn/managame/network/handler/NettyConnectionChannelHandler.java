package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.NettyConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

public class NettyConnectionChannelHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<IConnection> CONNECTION_KEY =
            AttributeKey.valueOf(NettyConnectionChannelHandler.class, "connection");

    protected final IConnectionHandler connectionHandler;

    public NettyConnectionChannelHandler(IConnectionHandler connectionHandler){
        this.connectionHandler = Objects.requireNonNull(connectionHandler, "connectionHandler");
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            activate(ctx);
            super.channelActive(ctx);
        } catch (Exception e) {
            ctx.channel().attr(CONNECTION_KEY).set(null);
            ctx.close();
            throw e;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        IConnection connection = connection(ctx.channel());
        if (connection != null) {
            connectionHandler.onMessage(connection, msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        IConnection connection = ctx.channel().attr(CONNECTION_KEY).getAndSet(null);
        if (connection != null) {
            connectionHandler.onDisconnect(connection);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        try {
            IConnection connection = connection(ctx.channel());
            if (connection != null) {
                connectionHandler.onException(connection, cause);
            }
        } finally {
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IConnection connection = connection(ctx.channel());
            if (connection != null) {
                connectionHandler.onIdle(connection);
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    public IConnection createConnection(Channel channel){
        return new NettyConnection(channel);
    }

    protected final IConnection activate(ChannelHandlerContext ctx) {
        IConnection existing = connection(ctx.channel());
        if (existing != null) {
            return existing;
        }
        IConnection connection = createConnection(ctx.channel());
        ctx.channel().attr(CONNECTION_KEY).set(connection);
        connectionHandler.onConnect(connection);
        return connection;
    }

    public static IConnection connection(Channel channel) {
        return channel.attr(CONNECTION_KEY).get();
    }

}
