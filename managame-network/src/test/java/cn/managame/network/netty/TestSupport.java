package cn.managame.network.netty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.TestCertificates;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

final class TestSupport {
    static final EventLoopGroup CLIENT_GROUP = NetworkTransports.newEventLoopGroup(2, "test-client");

    /** A reusable String codec: the same instances serve every server and client that adds it. */
    static final HandlerChain STRING_CODEC = HandlerChain.builder()
            .addLast("string-decoder", new StringDecoder(UTF_8))
            .addLast("string-encoder", new StringEncoder(UTF_8))
            .build();

    static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);

    private TestSupport() {}

    /** Quick start on an ephemeral loopback port. */
    static NetworkServer serve(ConnectionInitializer.Builder initializer) {
        return NetworkServer.start(LOCAL, initializer.build());
    }

    /** On the application's own EventLoops; the server fills in the channel type. */
    static NetworkServer serve(EventLoopGroup boss, EventLoopGroup worker, ConnectionInitializer.Builder initializer) {
        return NetworkServer.start(new ServerBootstrap().group(boss, worker).childHandler(initializer.build()), LOCAL);
    }

    /** Records callbacks as strings and echoes each message. */
    static class RecordingHandler implements ConnectionHandler {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Connection> connections = new LinkedBlockingQueue<>();

        @Override public void onActive(Connection connection) { connections.add(connection); events.add("active"); }
        @Override public void onRead(Connection connection, Object message) {
            switch (message) {
                case String text -> {
                    events.add("read:" + text);
                    connection.write("echo:" + text);
                }
                case ByteBuf bytes -> {
                    events.add("read:" + bytes.toString(UTF_8));
                    connection.write(bytes.retain());
                }
                case TextWebSocketFrame frame -> {
                    events.add("read:frame:" + frame.text());
                    connection.write(new TextWebSocketFrame("echo:" + frame.text()));
                }
                default -> events.add("read:" + message);
            }
        }
        @Override public void onInActive(Connection connection) { events.add("inactive"); }
        @Override public void onEvent(Connection connection, Object event) { events.add("event:" + event.getClass().getSimpleName()); }
        @Override public void onException(Connection connection, Throwable cause) { events.add("exception:" + cause.getClass().getSimpleName()); }

        String next() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "timed out waiting for a ConnectionHandler callback");
            return event;
        }

        String poll(long millis) throws InterruptedException { return events.poll(millis, TimeUnit.MILLISECONDS); }
    }

    /** A raw Netty client; inbound messages after the given handlers are collected as descriptions. */
    static final class TestClient implements AutoCloseable {
        final Channel channel;
        final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        final CountDownLatch handshake = new CountDownLatch(1);

        TestClient(int port, Consumer<ChannelPipeline> pipeline) {
            channel = new Bootstrap().group(CLIENT_GROUP).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<>() {
                        @Override protected void initChannel(Channel ch) {
                            pipeline.accept(ch.pipeline());
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    received.add(describe(message));
                                }
                                @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                    if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE)
                                        handshake.countDown();
                                    ReferenceCountUtil.release(event);
                                }
                            });
                        }
                    })
                    .connect("127.0.0.1", port).syncUninterruptibly().channel();
        }

        /** Connects without waiting for the handshake. */
        static TestClient webSocket(int port, String uri, SslContext ssl, Consumer<ChannelPipeline> after) {
            URI target = URI.create(uri);
            return new TestClient(port, p -> {
                if (ssl != null) p.addLast(ssl.newHandler(p.channel().alloc(), "localhost", port));
                p.addLast(new HttpClientCodec(), new HttpObjectAggregator(65536));
                p.addLast(new WebSocketClientProtocolHandler(WebSocketClientProtocolConfig.newBuilder()
                        .webSocketUri(target).handleCloseFrames(false).build()));
                after.accept(p);
            });
        }

        TestClient awaitHandshake() {
            try { assertTrue(handshake.await(5, TimeUnit.SECONDS), "WebSocket handshake did not complete"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return this;
        }

        void send(Object message) { channel.writeAndFlush(message).syncUninterruptibly(); }

        Object next() throws InterruptedException {
            Object message = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "timed out waiting for a server message");
            return message;
        }

        boolean awaitClosed() { return channel.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS); }

        @Override public void close() { channel.close().syncUninterruptibly(); }

        private static Object describe(Object message) {
            try {
                return switch (message) {
                    case BinaryWebSocketFrame f -> "binary:" + f.content().toString(UTF_8);
                    case TextWebSocketFrame f -> "text:" + f.text();
                    case CloseWebSocketFrame f -> "close:" + f.statusCode();
                    case FullHttpResponse r -> "http:" + r.status().code();
                    case ByteBuf b -> b.toString(UTF_8);
                    default -> message;
                };
            } finally {
                ReferenceCountUtil.release(message);
            }
        }
    }

    static SslContext insecureClientSsl() throws Exception {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    /** A self-signed PKCS#12 key store created with the JDK's keytool. */
    static Path selfSignedKeyStore(Path directory, char[] password) throws Exception {
        return TestCertificates.selfSignedKeyStore(directory, password);
    }
}
