package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connector.WebSocketConnectOptions;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.net.URI;
import java.util.List;
import java.util.function.*;

final class NetworkPipeline {
    static final String TLS = "managame-tls";
    static final String WS_PROTOCOL = "managame-websocket";
    static final String TRANSPORT = "managame-transport";
    static final String HANDLER = "managame-connection";
    static final int MAX_HTTP_CONTENT = 65536;

    static void install(Channel channel, ConnectionHandler handler, List<Consumer<ChannelPipeline>> configurers,
                        SslContext ssl, String peerHost, int peerPort, boolean webSocket, String path, URI uri,
                        WebSocketConnectOptions options, int maxMessageSize, ConnectAttempt attempt,
                        BooleanSupplier admit, Runnable established) {
        ChannelPipeline p = channel.pipeline();
        ConnectionHandlerAdapter adapter = new ConnectionHandlerAdapter(handler, cause -> {
            if (attempt != null) attempt.networkFailure(cause);
            else NetworkSupport.log("Pipeline failed before establishment", cause);
            channel.close();
        });
        // A void-promise failure starts at the pipeline head. Established write failures must
        // bypass WS protocol handlers, whose exceptionCaught would otherwise close automatically.
        p.addLast("managame-write-errors", new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                if (adapter.hasConnection()) p.context(TRANSPORT).fireExceptionCaught(cause);
                else ctx.fireExceptionCaught(cause);
            }
        });
        if (ssl != null) {
            SslHandler sslHandler;
            if (ssl.isClient()) {
                sslHandler = ssl.newHandler(channel.alloc(), peerHost, peerPort);
                var parameters = sslHandler.engine().getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                sslHandler.engine().setSSLParameters(parameters);
            } else sslHandler = ssl.newHandler(channel.alloc());
            p.addLast(TLS, sslHandler);
        }
        if (webSocket) {
            p.addLast("managame-http", uri == null ? new HttpServerCodec() : new HttpClientCodec());
            p.addLast("managame-http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT));
            if (uri == null) {
                p.addLast("managame-websocket-path", new WebSocketPathHandler(path));
                p.addLast(WS_PROTOCOL, new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                        .websocketPath(path).checkStartsWith(true).maxFramePayloadLength(maxMessageSize)
                        .allowExtensions(false).build()));
            } else {
                p.addLast(WS_PROTOCOL, new WebSocketClientProtocolHandler(WebSocketClientProtocolConfig.newBuilder()
                        .webSocketUri(uri).customHeaders(options.headers()).subprotocol(options.subprotocol())
                        .maxFramePayloadLength(maxMessageSize).allowExtensions(false).build()));
            }
            p.addLast("managame-websocket-aggregate", new WebSocketFrameAggregator(maxMessageSize));
        }
        p.addLast(TRANSPORT, new TransportGate(ssl != null, webSocket, adapter, attempt, admit, established));
        if (webSocket) {
            p.addLast("managame-binary-in", new WebSocketBinaryFrameDecoder());
            p.addLast("managame-binary-out", new WebSocketBinaryFrameEncoder());
        }
        for (var configurer : configurers) configurer.accept(p);
        p.addLast(HANDLER, adapter);
    }
}