package cn.managame.network.tests;

import cn.managame.network.netty.transport.HttpNetworkServer;
import cn.managame.network.netty.transport.NetworkResources;
import cn.managame.network.netty.transport.TcpNetworkServer;
import cn.managame.network.netty.transport.WsNetworkServer;

import cn.managame.network.*;
import cn.managame.network.netty.transport.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run in an IDE; optional arguments: PEM certificate chain and PEM private key. */
public final class GatewayExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException(
                    "Expected no arguments, or certificate.pem private-key.pem");
        try (var resources = NetworkResources.builder().ioThreads(4).httpThreads(2).build()) {
            var tcp =
                    TcpNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 7000)
                            .handlerFactory(EchoHandler::new)
                            .pipeline(
                                    (connection, pipeline) ->
                                            pipeline.addLast(
                                                    new LengthFieldBasedFrameDecoder(
                                                            65536, 0, 4, 0, 4),
                                                    new LengthFieldPrepender(4)))
                            .build();
            var ws =
                    WsNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 7001)
                            .handlerFactory(EchoHandler::new)
                            .webSocketServer(config -> config.websocketPath("/game"))
                            .build();
            var http =
                    HttpNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 8080)
                            .httpPipeline(pipeline -> pipeline.addLast(new HealthHandler()))
                            .build();
            List<NetworkServer> servers = new ArrayList<>(List.of(tcp, ws, http));
            if (args.length == 2)
                servers.add(
                        WsNetworkServer.builder()
                                .resources(resources)
                                .listen("127.0.0.1", 7002)
                                .handlerFactory(EchoHandler::new)
                                .webSocketServer(config -> config.websocketPath("/game"))
                                .sslContext(
                                        SslContextBuilder.forServer(
                                                        new File(args[0]), new File(args[1]))
                                                .build())
                                .build());
            try {
                for (var server : servers) server.start();
                System.out.println("TCP: " + tcp.boundAddresses());
                System.out.println("WS: " + ws.boundAddresses());
                System.out.println("HTTP: " + http.boundAddresses());
                System.out.println("Press Enter to stop.");
                System.in.read();
            } finally {
                Throwable failure = null;
                for (var server : servers.reversed()) {
                    try {
                        server.stop();
                    } catch (Throwable t) {
                        if (failure == null) failure = t;
                        else failure.addSuppressed(t);
                    }
                }
                if (failure != null) throw new NetworkException("Gateway stop failed", failure);
            }
        }
    }

    static final class EchoHandler implements NetworkHandler {
        public void onMessage(Connection connection, Object message) {
            ReferenceCountUtil.retain(message);
            if (!connection.write(message)) ReferenceCountUtil.release(message);
        }
    }

    static final class HealthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            var response =
                    new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8));
            HttpUtil.setContentLength(response, 2);
            boolean keep = HttpUtil.isKeepAlive(request);
            HttpUtil.setKeepAlive(response, keep);
            var write = ctx.writeAndFlush(response);
            if (!keep) write.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
