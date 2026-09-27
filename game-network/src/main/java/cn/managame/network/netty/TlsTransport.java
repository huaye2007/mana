package cn.managame.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.util.function.Function;

/** TLS composes with a payload transport; client/server setup is explicit. */
final class TlsTransport implements ChannelTransport {
    static final String HANDLER = "managame-tls";
    private final Function<Channel, SslHandler> factory;
    private final ChannelTransport payload;

    private TlsTransport(Function<Channel, SslHandler> factory, ChannelTransport payload) {
        this.factory = factory;
        this.payload = payload;
    }

    static ChannelTransport server(SslContext ssl, ChannelTransport payload) {
        return new TlsTransport(channel -> ssl.newHandler(channel.alloc()), payload);
    }

    static ChannelTransport client(SslContext ssl, String host, int port, ChannelTransport payload) {
        return new TlsTransport(channel -> {
            SslHandler handler = ssl.newHandler(channel.alloc(), host, port);
            var parameters = handler.engine().getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            handler.engine().setSSLParameters(parameters);
            return handler;
        }, payload);
    }

    @Override public void addProtocolHandlers(ChannelPipeline pipeline, ConnectionLifecycle lifecycle) {
        var handshake = lifecycle.expectHandshake();
        pipeline.addLast(HANDLER, factory.apply(pipeline.channel()));
        pipeline.addLast("managame-tls-handshake", new ChannelInboundHandlerAdapter() {
            @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof SslHandshakeCompletionEvent ssl) {
                    if (ssl.isSuccess()) handshake.succeed();
                    else lifecycle.fail(ssl.cause());
                } else ctx.fireUserEventTriggered(event);
            }
        });
        payload.addProtocolHandlers(pipeline, lifecycle);
    }

    @Override public void addPayloadHandlers(ChannelPipeline pipeline) {
        payload.addPayloadHandlers(pipeline);
    }
}
