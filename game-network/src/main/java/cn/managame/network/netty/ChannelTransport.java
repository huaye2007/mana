package cn.managame.network.netty;

import io.netty.channel.ChannelPipeline;

/** Internal transport assembly, deliberately independent of client/server ownership. */
interface ChannelTransport {
    ChannelTransport TCP = (pipeline, lifecycle) -> {};

    /** Add protocol handlers and register handshake prerequisites before activation. */
    void addProtocolHandlers(ChannelPipeline pipeline, ConnectionLifecycle lifecycle);

    /** Adapt established transport payloads before the application's codecs. */
    default void addPayloadHandlers(ChannelPipeline pipeline) {}
}
