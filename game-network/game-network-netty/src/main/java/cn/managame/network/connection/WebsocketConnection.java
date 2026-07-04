package cn.managame.network.connection;

import io.netty.channel.Channel;

public class WebsocketConnection extends NettyConnection{
    public WebsocketConnection(long connectionId, Channel channel) {
        super(connectionId, channel);
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.WEBSOCKET;
    }
}
