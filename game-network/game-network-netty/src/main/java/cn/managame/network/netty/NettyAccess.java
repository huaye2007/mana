package cn.managame.network.netty;

import cn.managame.network.Connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.Future;

import java.util.function.Consumer;

/** Direct access to native Netty integration points. */
public final class NettyAccess {
    private NettyAccess() {}

    public static Channel channel(Connection connection) {
        if (!(connection instanceof NettyConnection netty))
            throw new IllegalArgumentException("Not a Netty connection");
        return netty.channel;
    }

    public static Future<?> editPipeline(Connection connection, Consumer<ChannelPipeline> editor) {
        Channel channel = channel(connection);
        return channel.eventLoop().submit(() -> editor.accept(channel.pipeline()));
    }
}
