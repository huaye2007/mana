package cn.managame.network.netty.transport;

import cn.managame.network.*;

import io.netty.channel.ChannelPipeline;

import java.util.Objects;
import java.util.function.*;

/** Common client resources, options and user pipeline configuration. */
abstract class ClientBuilder<B extends ClientBuilder<B>> extends OptionsBuilder<B> {
    NetworkResources resources;
    Supplier<? extends NetworkHandler> factory;
    BiConsumer<Connection, ChannelPipeline> pipeline;

    public B resources(NetworkResources value) {
        resources = Objects.requireNonNull(value);
        return self();
    }

    public B handlerFactory(Supplier<? extends NetworkHandler> value) {
        factory = Objects.requireNonNull(value);
        return self();
    }

    public B pipeline(BiConsumer<Connection, ChannelPipeline> value) {
        pipeline = Objects.requireNonNull(value);
        return self();
    }
}
