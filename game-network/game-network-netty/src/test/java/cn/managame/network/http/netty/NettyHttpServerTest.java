package cn.managame.network.http.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServerTest {

    @Test
    void autoProtocolAcceptsHttp1WithoutLeakingProtocolToBusiness() throws Exception {
        NettyHttpServer server = NettyHttpServer.builder((request, responder) -> {
                    if ("GET".equals(request.method()) && "/health".equals(request.uri())) {
                        responder.text("ok");
                    } else {
                        responder.send(404, new byte[0]);
                    }
                })
                .bind("127.0.0.1", 0)
                .build();
        try {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            String response;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /health HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] buffer = new byte[1024];
                int read = socket.getInputStream().read(buffer);
                response = read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
            }
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.endsWith("ok"), response);
        } finally {
            server.stop();
        }
    }

    @Test
    void autoProtocolAcceptsHttp2PriorKnowledgeWithoutLeakingProtocolToBusiness() throws Exception {
        NettyHttpServer server = NettyHttpServer.builder((request, responder) ->
                        responder.text(request.method() + " " + request.uri()))
                .bind("127.0.0.1", 0)
                .maxConcurrentStreams(7)
                .build();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel parent = null;
        CountDownLatch settingsReceived = new CountDownLatch(1);
        AtomicReference<Long> concurrentStreams = new AtomicReference<>();
        try {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            parent = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<Http2SettingsFrame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext context, Http2SettingsFrame frame) {
                                    concurrentStreams.set(frame.settings().maxConcurrentStreams());
                                    settingsReceived.countDown();
                                }
                            });
                            channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                        }
                    })
                    .connect("127.0.0.1", port)
                    .syncUninterruptibly()
                    .channel();
            assertTrue(settingsReceived.await(3, TimeUnit.SECONDS));
            assertEquals(7L, concurrentStreams.get());

            CountDownLatch responseReceived = new CountDownLatch(1);
            AtomicReference<String> responseBody = new AtomicReference<>();
            Http2StreamChannel stream = new Http2StreamChannelBootstrap(parent)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false));
                            channel.pipeline().addLast(new HttpObjectAggregator(1024));
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext context, FullHttpResponse response) {
                                    assertEquals(200, response.status().code());
                                    responseBody.set(response.content().toString(StandardCharsets.UTF_8));
                                    responseReceived.countDown();
                                }
                            });
                        }
                    })
                    .open()
                    .syncUninterruptibly()
                    .getNow();
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/h2");
            request.headers().set(HttpHeaderNames.HOST, "localhost");
            stream.writeAndFlush(request).syncUninterruptibly();

            assertTrue(responseReceived.await(3, TimeUnit.SECONDS));
            assertEquals("GET /h2", responseBody.get());
        } finally {
            if (parent != null) {
                parent.close().syncUninterruptibly();
            }
            clientGroup.shutdownGracefully().syncUninterruptibly();
            server.stop();
        }
    }

    @Test
    void autoProtocolAcceptsH2cUpgrade() throws Exception {
        NettyHttpServer server = NettyHttpServer.builder((request, responder) -> responder.text("ok"))
                .bind("127.0.0.1", 0)
                .build();
        try {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            String response;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /h2c HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: Upgrade, HTTP2-Settings\r\n"
                        + "Upgrade: h2c\r\n"
                        + "HTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] buffer = new byte[1024];
                int read = socket.getInputStream().read(buffer);
                response = read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
            }
            assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"), response);
        } finally {
            server.stop();
        }
    }

    @Test
    void tlsAutoProtocolNegotiatesHttp2WithAlpn() throws Exception {
        ApplicationProtocolConfig alpn = new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1);
        SslContext serverTls;
        try (InputStream certificate = resource("/tls/localhost-cert.pem");
             InputStream privateKey = resource("/tls/localhost-key.pem")) {
            serverTls = SslContextBuilder.forServer(certificate, privateKey)
                    .applicationProtocolConfig(alpn)
                    .build();
        }
        SslContext clientTls = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(alpn)
                .build();
        NettyHttpServer server = NettyHttpServer.builder((request, responder) -> responder.text("ok"))
                .bind("127.0.0.1", 0)
                .sslContext(serverTls)
                .build();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel client = null;
        try {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            client = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("ssl",
                                    clientTls.newHandler(channel.alloc(), "localhost", port));
                            channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                            channel.pipeline().addLast(
                                    new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                        }
                    })
                    .connect("127.0.0.1", port)
                    .syncUninterruptibly()
                    .channel();

            SslHandler sslHandler = client.pipeline().get(SslHandler.class);
            sslHandler.handshakeFuture().syncUninterruptibly();
            assertEquals(ApplicationProtocolNames.HTTP_2, sslHandler.applicationProtocol());
        } finally {
            if (client != null) {
                client.close().syncUninterruptibly();
            }
            clientGroup.shutdownGracefully().syncUninterruptibly();
            server.stop();
        }
    }

    private static InputStream resource(String name) {
        return java.util.Objects.requireNonNull(
                NettyHttpServerTest.class.getResourceAsStream(name), "missing test resource: " + name);
    }

    @Test
    void oversizedHttp1BodyIsRejectedBeforeBusinessHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NettyHttpServer server = NettyHttpServer.builder((request, responder) -> {
                    calls.incrementAndGet();
                    responder.text("unexpected");
                })
                .bind("127.0.0.1", 0)
                .protocol(NettyHttpProtocol.HTTP1)
                .maxContentLength(4)
                .build();
        try {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            String response;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("POST /upload HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 5\r\n"
                        + "Connection: close\r\n\r\n"
                        + "12345").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] buffer = new byte[1024];
                int read = socket.getInputStream().read(buffer);
                response = read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
            }
            assertTrue(response.startsWith("HTTP/1.1 413"), response);
            assertEquals(0, calls.get());
        } finally {
            server.stop();
        }
    }

}
