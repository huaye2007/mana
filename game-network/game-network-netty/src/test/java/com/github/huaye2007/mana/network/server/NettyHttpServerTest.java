package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.http.HttpProtocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServerTest {

    @Test
    void startsHttp1ServerAndHandlesRequest() throws Exception {
        int port = availablePort();
        NetworkHttpServerConfig config = new NetworkHttpServerConfig(port);
        config.setHost("127.0.0.1");
        config.setHttpProtocol(HttpProtocol.HTTP1);
        NettyHttpServer server = new NettyHttpServer(config, exchange ->
                exchange.writeResponse(200, exchange.getProtocol() + " " + exchange.getMethod() + " "
                        + exchange.getUri() + " " + new String(exchange.getBody(), StandardCharsets.UTF_8)));

        try {
            server.start();

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
                    .POST(HttpRequest.BodyPublishers.ofString("body"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("HTTP/1.1 POST /hello body", response.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void startsHttp2ServerAndHandlesStreamRequest() throws Exception {
        int port = availablePort();
        NetworkHttpServerConfig config = new NetworkHttpServerConfig(port);
        config.setHost("127.0.0.1");
        config.setHttpProtocol(HttpProtocol.HTTP2);
        NettyHttpServer server = new NettyHttpServer(config, exchange ->
                exchange.writeResponse(200, exchange.getProtocol() + " " + exchange.getMethod() + " "
                        + exchange.getUri()));

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            server.start();
            AtomicReference<String> body = new AtomicReference<>();
            CountDownLatch responseLatch = new CountDownLatch(1);
            Channel parentChannel = connectHttp2Client(port, group);
            Http2StreamChannel streamChannel = openHttp2Stream(parentChannel, body, responseLatch);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.GET, "/h2", Unpooled.EMPTY_BUFFER);
            request.headers().set(HttpHeaderNames.HOST, "127.0.0.1");
            streamChannel.writeAndFlush(request).sync();

            assertTrue(responseLatch.await(3, TimeUnit.SECONDS));
            assertEquals("HTTP/2 GET /h2", body.get());

            streamChannel.close().syncUninterruptibly();
            parentChannel.close().syncUninterruptibly();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
            server.stop();
        }
    }

    private static Channel connectHttp2Client(int port, EventLoopGroup group) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                    }
                });
        return bootstrap.connect("127.0.0.1", port).sync().channel();
    }

    private static Http2StreamChannel openHttp2Stream(Channel parentChannel, AtomicReference<String> body,
                                                     CountDownLatch responseLatch) throws Exception {
        Http2StreamChannelBootstrap streamBootstrap = new Http2StreamChannelBootstrap(parentChannel);
        return streamBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false));
                channel.pipeline().addLast(new HttpObjectAggregator(65536));
                channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                        byte[] bytes = new byte[response.content().readableBytes()];
                        response.content().getBytes(response.content().readerIndex(), bytes);
                        body.set(new String(bytes, StandardCharsets.UTF_8));
                        responseLatch.countDown();
                    }
                });
            }
        }).open().get(3, TimeUnit.SECONDS);
    }

    private static int availablePort() throws IOException {
        try(ServerSocket socket = new ServerSocket(0)){
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
