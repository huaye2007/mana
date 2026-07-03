package com.github.huaye2007.mana.network.handler;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.WebsocketConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public class WebsocketConnectionChannelHandler extends NettyConnectionChannelHandler{
    public WebsocketConnectionChannelHandler(IConnectionHandler connectionHandler, ConnectionManager connectionManager) {
        super(connectionHandler, connectionManager);
    }

    public WebsocketConnectionChannelHandler(IConnectionHandler connectionHandler, ConnectionManager connectionManager,
                                             IConnectionIdGenerator connectionIdGenerator) {
        super(connectionHandler, connectionManager, connectionIdGenerator);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE){
            IConnection connection = createConnection(ctx.channel());
            connectionManager.addConnection(connection, ctx.channel());
            connectionHandler.onConnect(connection);
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public IConnection createConnection(Channel channel){
        return new WebsocketConnection(nextConnectionId(),channel);
    }
}
