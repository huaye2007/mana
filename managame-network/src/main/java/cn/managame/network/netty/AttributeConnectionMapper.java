package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import io.netty.channel.Channel;

/** The default mapper. Clearing the attribute on remove ends callbacks for the channel after onInActive. */
final class AttributeConnectionMapper implements ConnectionMapper {
    static final AttributeConnectionMapper INSTANCE = new AttributeConnectionMapper();

    private AttributeConnectionMapper() {}

    @Override public Connection create(Channel channel) {
        NettyConnection connection = new NettyConnection(channel);
        channel.attr(NetworkAttributes.CONNECTION).set(connection);
        return connection;
    }

    @Override public Connection get(Channel channel) { return channel.attr(NetworkAttributes.CONNECTION).get(); }

    @Override public void remove(Channel channel) { channel.attr(NetworkAttributes.CONNECTION).set(null); }
}
