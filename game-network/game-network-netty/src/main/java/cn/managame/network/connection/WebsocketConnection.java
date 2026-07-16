package cn.managame.network.connection;

import io.netty.channel.Channel;

public class WebsocketConnection extends NettyConnection{
    public WebsocketConnection(Channel channel) {
        super(channel);
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.WEBSOCKET;
    }
}
