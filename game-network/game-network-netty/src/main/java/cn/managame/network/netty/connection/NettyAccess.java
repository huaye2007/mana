package cn.managame.network.netty.connection;

import cn.managame.network.Connection;
import cn.managame.network.NetworkHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.util.function.Consumer;

/** Direct access to native Netty integration points. */
public final class NettyAccess {
    private NettyAccess() {}

    /** Native pipeline bridge; its implementation remains package-private. */
    public static ChannelHandler handler(
            NettyConnection connection, NetworkHandler handler,
            Promise<Connection> readiness) {
        return new NetworkHandlerBridge(connection, handler, readiness);
    }

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
