package cn.managame.network.netty;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.network.connection.Connection;
import cn.managame.network.connector.ConnectCallback;
import cn.managame.network.error.NetworkException;
import io.netty.channel.Channel;
import io.netty.handler.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeTlsTest extends NetworkTestSupport {
    private static final class Result implements ConnectCallback {
        final Promise<Throwable> failure = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        final AtomicInteger count = new AtomicInteger();
        public void onSuccess(Connection connection) {
            count.incrementAndGet();
            failure.tryFailure(new AssertionError("Unexpected connection success"));
        }
        public void onFailure(Throwable cause) { count.incrementAndGet(); failure.trySuccess(cause); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void silentTlsPeerTimesOutOrIsCancelledWithoutBusinessCallbacks(boolean cancel) throws Exception {
        Probe probe = new Probe();
        Result result = new Result();
        AtomicReference<Channel> channel = new AtomicReference<>();
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = NetworkClient.builder().handler(probe).pipeline(p -> {
                 channel.set(p.channel());
                 SslHandler ssl = clientSsl(clientTls, p.channel(), "localhost", listener.getLocalPort());
                 ssl.setHandshakeTimeoutMillis(cancel ? 10000 : 100);
                 p.addFirst("ssl", ssl);
             }).build()) {
            listener.setSoTimeout(5000);
            client.connectAsync(listener.getLocalSocketAddress(), result);
            try (var peer = listener.accept()) {
                if (cancel) client.close();
                Throwable failure = get(result.failure);
                if (cancel) assertInstanceOf(IllegalStateException.class, failure);
                else {
                    assertInstanceOf(NetworkException.class, failure);
                    assertInstanceOf(SslHandshakeTimeoutException.class, failure.getCause());
                }
                channel.get().closeFuture().sync();
                assertEquals(1, result.count.get());
                assertEquals(0, probe.connections.get());
                assertEquals(0, probe.disconnects.get());
                assertTrue(probe.errors.isEmpty());
            }
        }
    }

    @Test void wssWaitsForUpgradeAfterTlsSucceeds() throws Exception {
        Probe probe = new Probe(), serverProbe = new Probe();
        Result result = new Result();
        AtomicReference<SslHandler> handler = new AtomicReference<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(serverProbe)
                .pipeline(p -> p.addFirst("ssl", serverTls.newHandler(p.channel().alloc()))).build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (var client = NetworkClient.builder().webSocket().handler(probe).pipeline(p -> {
                SslHandler ssl = clientSsl(clientTls, p.channel(), "127.0.0.1", port);
                handler.set(ssl);
                p.addFirst("ssl", ssl);
            }).build()) {
                client.connectAsync(URI.create("wss://127.0.0.1:" + port + "/game"), result);
                take(serverProbe.connected);
                assertTrue(handler.get().handshakeFuture().await(5, TimeUnit.SECONDS));
                assertTrue(handler.get().handshakeFuture().isSuccess());
                // The TLS-only peer receives the Upgrade request but never accepts it.
                take(serverProbe.messages);
                assertEquals(0, probe.connections.get());
                assertFalse(result.failure.isDone());
                client.close();
                assertInstanceOf(IllegalStateException.class, get(result.failure));
                assertEquals(1, result.count.get());
                assertEquals(0, probe.disconnects.get());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"tls-trust", "tls-host", "wss-trust", "wss-host"})
    void certificateValidationFailureNeverCreatesConnection(String mode) throws Exception {
        boolean ws = mode.startsWith("wss"), wrongHost = mode.endsWith("host");
        Probe probe = new Probe();
        SslContext context = wrongHost ? clientTls
                : SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();
        var builder = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe())
                .pipeline(p -> p.addFirst("ssl", serverTls.newHandler(p.channel().alloc())));
        if (ws) builder.webSocket("/game");
        try (var server = builder.build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            var cb = NetworkClient.builder().handler(probe).pipeline(p -> p.addFirst("ssl",
                    clientSsl(context, p.channel(), wrongHost ? "wrong.example" : "127.0.0.1", port)));
            if (ws) cb.webSocket();
            try (var client = cb.build()) {
                NetworkException error = assertThrows(NetworkException.class, () -> {
                    if (ws) client.connect(URI.create("wss://127.0.0.1:" + port + "/game"));
                    else client.connect(server.localAddress());
                });
                assertInstanceOf(javax.net.ssl.SSLException.class, error.getCause());
                assertEquals(0, probe.connections.get());
                assertEquals(0, probe.disconnects.get());
                assertTrue(probe.errors.isEmpty());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"last", "duplicate", "before-ssl", "configuration"})
    void invalidTlsPipelineFailsBeforeConnectionDelivery(String mode) throws Exception {
        Probe probe = new Probe();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (var client = NetworkClient.builder().handler(probe).pipeline(p -> {
                SslHandler ssl = clientSsl(clientTls, p.channel(), "127.0.0.1", port);
                if (mode.equals("last")) p.addLast("ssl", ssl);
                else p.addFirst("ssl", ssl);
                if (mode.equals("duplicate"))
                    p.addFirst("another-ssl", clientSsl(clientTls, p.channel(), "127.0.0.1", port));
                if (mode.equals("before-ssl"))
                    p.addFirst("decoder", new io.netty.handler.codec.FixedLengthFrameDecoder(4));
                if (mode.equals("configuration")) throw new IllegalArgumentException("configuration");
            }).build()) {
                NetworkException error = assertThrows(NetworkException.class, () -> client.connect(server.localAddress()));
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
                assertEquals(0, probe.connections.get());
                assertEquals(0, probe.disconnects.get());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void webSocketSchemeMustMatchExplicitTlsConfiguration(boolean tls) throws Exception {
        Probe probe = new Probe();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").handler(new Probe()).build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            var builder = NetworkClient.builder().webSocket().handler(probe);
            if (tls) builder.pipeline(p -> p.addFirst("ssl", clientSsl(clientTls, p.channel(), "127.0.0.1", port)));
            try (var client = builder.build()) {
                NetworkException error = assertThrows(NetworkException.class,
                        () -> client.connect(URI.create((tls ? "ws" : "wss") + "://127.0.0.1:" + port + "/game")));
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
                assertTrue(error.getCause().getMessage().contains(tls ? "ws URI" : "wss URI"));
                assertEquals(0, probe.connections.get());
                assertEquals(0, probe.disconnects.get());
            }
        }
    }

    @Test void tlsRejectsPlaintextBeforeHttpOrBusinessHandlers() throws Exception {
        Probe probe = new Probe();
        AtomicInteger httpMessages = new AtomicInteger();
        Promise<Channel> accepted = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").handler(probe)
                .pipeline(p -> {
                    p.addFirst("ssl", serverTls.newHandler(p.channel().alloc()));
                    p.addBefore("network-http", "http-observer", new io.netty.channel.ChannelInboundHandlerAdapter() {
                        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object message) {
                            httpMessages.incrementAndGet();
                            ctx.fireChannelRead(message);
                        }
                    });
                    accepted.trySuccess(p.channel());
                }).build(); var socket = new Socket()) {
            server.start();
            socket.connect(server.localAddress());
            Channel channel = get(accepted);
            socket.getOutputStream().write("GET /game HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
            channel.eventLoop().submit(() -> {}).sync();
            assertEquals(0, httpMessages.get());
            assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get());
            assertTrue(probe.errors.isEmpty());
        }
    }

}
