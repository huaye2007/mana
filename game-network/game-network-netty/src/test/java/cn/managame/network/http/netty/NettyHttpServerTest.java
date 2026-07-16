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
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                .build();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel parent = null;
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
                            channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                        }
                    })
                    .connect("127.0.0.1", port)
                    .syncUninterruptibly()
                    .channel();

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
}
