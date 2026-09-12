package cn.managame.network.netty;

import cn.managame.network.*;

import io.netty.channel.ChannelPipeline;

import java.util.Objects;
import java.util.function.*;

/** Shared handler configuration for TCP and WebSocket server builders. */
abstract class RealtimeServerBuilder<B extends RealtimeServerBuilder<B>> extends ServerBuilder<B> {
    Supplier<? extends NetworkHandler> factory;
    BiConsumer<Connection, ChannelPipeline> pipeline;

    public B handlerFactory(Supplier<? extends NetworkHandler> value) {
        factory = Objects.requireNonNull(value);
        return self();
    }

    public B pipeline(BiConsumer<Connection, ChannelPipeline> value) {
        pipeline = Objects.requireNonNull(value);
        return self();
    }
}
