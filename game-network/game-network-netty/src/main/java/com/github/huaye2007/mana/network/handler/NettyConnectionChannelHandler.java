package com.github.huaye2007.mana.network.handler;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.DefaultConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.NettyConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

public class NettyConnectionChannelHandler extends ChannelInboundHandlerAdapter {

    private static final IConnectionIdGenerator DEFAULT_CONNECTION_ID_GENERATOR = new DefaultConnectionIdGenerator();

    protected final IConnectionHandler connectionHandler;
    protected final ConnectionManager connectionManager;
    protected final IConnectionIdGenerator connectionIdGenerator;

    public NettyConnectionChannelHandler(IConnectionHandler connectionHandler,ConnectionManager connectionManager){
        this(connectionHandler, connectionManager, DEFAULT_CONNECTION_ID_GENERATOR);
    }

    public NettyConnectionChannelHandler(IConnectionHandler connectionHandler, ConnectionManager connectionManager,
                                         IConnectionIdGenerator connectionIdGenerator){
        this.connectionHandler = connectionHandler;
        this.connectionManager = connectionManager;
        this.connectionIdGenerator = connectionIdGenerator;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            IConnection connection = createConnection(ctx.channel());
            connectionManager.addConnection(connection,ctx.channel());
            connectionHandler.onConnect(connection);
            super.channelActive(ctx);
        } catch (Exception e) {
            connectionManager.removeConnectionByChannel(ctx.channel());
            ctx.close();
            throw e;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        IConnection connection = connectionManager.getConnection(ctx.channel());
        if (connection != null) {
            connectionHandler.onMessage(connection, msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        IConnection connection = connectionManager.removeConnectionByChannel(ctx.channel());
        if (connection != null) {
            connectionHandler.onDisconnect(connection);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        try {
            IConnection connection = connectionManager.getConnection(ctx.channel());
            connectionHandler.onException(connection, cause);
        } finally {
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IConnection connection = connectionManager.getConnection(ctx.channel());
            if (connection != null) {
                connectionHandler.onIdle(connection);
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    public IConnection createConnection(Channel channel){
        return new NettyConnection(nextConnectionId(),channel);
    }

    protected long nextConnectionId(){
        return connectionIdGenerator.nextId();
    }

}
