package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Defines assembly order only. Transports own protocol details, owners own admission. */
final class NetworkChannelInitializer extends ChannelInitializer<Channel> {
    static final String LIFECYCLE = "managame-transport";
    static final String HANDLER = "managame-connection";
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> configurers;
    private final ChannelTransport transport;
    private final Function<Channel, ConnectionEstablishment> establishmentFactory;

    NetworkChannelInitializer(ConnectionHandler handler, List<Consumer<ChannelPipeline>> configurers,
                              ChannelTransport transport,
                              Function<Channel, ConnectionEstablishment> establishmentFactory) {
        this.handler = handler;
        this.configurers = List.copyOf(configurers);
        this.transport = transport;
        this.establishmentFactory = establishmentFactory;
    }

    @Override protected void initChannel(Channel channel) {
        ConnectionLifecycle lifecycle = new ConnectionLifecycle(channel, handler, establishmentFactory.apply(channel));
        try {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast("managame-write-errors", lifecycle.writeErrors());
            transport.addProtocolHandlers(pipeline, lifecycle);
            pipeline.addLast(LIFECYCLE, lifecycle);
            transport.addPayloadHandlers(pipeline);
            for (var configurer : configurers) configurer.accept(pipeline);
            pipeline.addLast(HANDLER, lifecycle.adapter());
            lifecycle.initialized();
        } catch (Throwable cause) {
            lifecycle.fail(cause);
        }
    }
}
