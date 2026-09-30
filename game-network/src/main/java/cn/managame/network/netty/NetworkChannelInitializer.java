package cn.managame.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import java.util.List;
import java.util.function.Consumer;

/** Shared pipeline assembly using native TLS/WebSocket handlers. */
final class NetworkChannelInitializer {
    static final String HANDLER = "network-connection";

    private NetworkChannelInitializer() {}

    static void configure(Channel channel, List<Consumer<ChannelPipeline>> configurers,
                          WebSocketTransport webSocket, ConnectionHandlerAdapter adapter) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            WriteErrors writeErrors = new WriteErrors(adapter);
            pipeline.addLast("network-write-errors", writeErrors);
            if (webSocket != null) webSocket.addProtocolHandlers(pipeline);
            // The last protocol context is stable; errors resume through payload/user codecs.
            writeErrors.applicationStart = pipeline.lastContext();
            if (webSocket != null) webSocket.addPayloadHandlers(pipeline);
            for (var configurer : configurers) configurer.accept(pipeline);

            SslHandler ssl = pipeline.get(SslHandler.class);
            if (ssl != null) {
                if (pipeline.first() != ssl
                        || pipeline.toMap().values().stream().filter(SslHandler.class::isInstance).count() != 1)
                    throw new IllegalArgumentException("Configure exactly one SslHandler using pipeline.addFirst");
            }
            if (webSocket != null) webSocket.verifyScheme(ssl != null);
            pipeline.addLast(HANDLER, adapter);
            adapter.initialized(ssl, webSocket != null);
        } catch (Throwable cause) {
            adapter.failEstablishment(cause);
        }
    }

    /** Ordinary void-promise write failures bypass protocol-specific automatic close policies. */
    private static final class WriteErrors extends ChannelInboundHandlerAdapter {
        private final ConnectionHandlerAdapter adapter;
        private ChannelHandlerContext applicationStart;

        WriteErrors(ConnectionHandlerAdapter adapter) { this.adapter = adapter; }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (adapter.hasConnection()) applicationStart.fireExceptionCaught(cause);
            else ctx.fireExceptionCaught(cause);
        }
    }
}
