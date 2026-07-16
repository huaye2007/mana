package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.WebsocketConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public class WebsocketConnectionChannelHandler extends NettyConnectionChannelHandler{
    public WebsocketConnectionChannelHandler(IConnectionHandler connectionHandler) {
        super(connectionHandler);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE){
            activate(ctx);
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public IConnection createConnection(Channel channel){
        return new WebsocketConnection(channel);
    }
}
