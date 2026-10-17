package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.internal.ListenerLifecycle;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.Consumer;

/** One-shot synchronous listener. Externally supplied EventLoopGroups remain caller-owned. */
public final class NetworkServer implements AutoCloseable {
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final WebSocketTransport webSocket;
    private final ListenerLifecycle lifecycle;

    NetworkServer(NetworkServerBuilder b) {
        handler = b.handler;
        pipelines = List.copyOf(b.pipelines);
        webSocket = b.path == null ? null : WebSocketTransport.server(b.path, b.maxMessageSize);
        lifecycle = new ListenerLifecycle("Server", b.address, b.factory, b.options, b.childOptions, b.boss, b.worker, null);
    }

    public static NetworkServerBuilder builder() { return new NetworkServerBuilder(); }

    public void start() {
        lifecycle.start(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { accept(channel); }
        });
    }

    private void accept(Channel channel) {
        if (lifecycle.isClosed()) {
            channel.close();
            return;
        }
        ConnectionHandlerAdapter adapter =
                new ConnectionHandlerAdapter(channel, handler, () -> !lifecycle.isClosed(), null);
        NetworkChannelInitializer.configure(channel, pipelines, webSocket, adapter);
    }

    public SocketAddress localAddress() { return lifecycle.localAddress(); }

    @Override public void close() { lifecycle.close(); }
}
