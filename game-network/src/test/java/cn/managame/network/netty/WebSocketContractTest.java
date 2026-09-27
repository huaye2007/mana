package cn.managame.network.netty;

import cn.managame.network.connection.*;
import cn.managame.network.connector.*;
import cn.managame.network.error.NetworkException;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketContractTest extends NetworkTestSupport {
    static ByteBuf bytes(String s) { return Unpooled.copiedBuffer(s, StandardCharsets.UTF_8); }
    static URI uri(NetworkServer server, String path) {
        return URI.create("ws://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + path);
    }

    @Test void frameCodecsTransferExactlyOneReference() {
        var decoder = new EmbeddedChannel(new WebSocketBinaryFrameDecoder());
        ByteBuf bytes = bytes("in");
        decoder.writeInbound(new BinaryWebSocketFrame(bytes));
        ByteBuf output = decoder.readInbound();
        assertSame(bytes, output); assertEquals(1, output.refCnt()); output.release();
        decoder.finishAndReleaseAll();
        var encoder = new EmbeddedChannel(new WebSocketBinaryFrameEncoder());
        ByteBuf outgoing = bytes("out");
        encoder.writeOutbound(outgoing);
        BinaryWebSocketFrame frame = encoder.readOutbound();
        assertSame(outgoing, frame.content()); assertEquals(1, outgoing.refCnt());
        frame.release(); assertEquals(0, outgoing.refCnt());
        encoder.finishAndReleaseAll();
    }

    @Test void fragmentsControlFramesAndTextRejection() throws Exception {
        Probe serverProbe = new Probe(), clientProbe = new Probe();
        AtomicReference<Channel> channel = new AtomicReference<>();
        CompletableFuture<String> pong = new CompletableFuture<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").handler(serverProbe).build();
             var client = NetworkClient.builder().webSocket().handler(clientProbe).pipeline(p -> {
                 channel.set(p.channel());
                 p.addBefore(NetworkPipeline.WS_PROTOCOL, "observe-pong", new ChannelInboundHandlerAdapter() {
                     public void channelRead(ChannelHandlerContext ctx, Object message) {
                         if (message instanceof PongWebSocketFrame frame)
                             pong.complete(frame.content().toString(StandardCharsets.UTF_8));
                         ctx.fireChannelRead(message);
                     }
                 });
             }).build()) {
            server.start();
            Connection c = client.connect(uri(server, "/game"));
            take(serverProbe.connected);
            channel.get().writeAndFlush(new BinaryWebSocketFrame(false, 0, bytes("ab"))).sync();
            channel.get().writeAndFlush(new ContinuationWebSocketFrame(true, 0, bytes("cd"))).sync();
            assertEquals("abcd", take(serverProbe.messages)); assertTrue(serverProbe.messages.isEmpty());
            channel.get().writeAndFlush(new PingWebSocketFrame(bytes("ping"))).sync();
            assertEquals("ping", get(pong)); assertTrue(serverProbe.messages.isEmpty());
            channel.get().writeAndFlush(new TextWebSocketFrame("unsupported")).sync();
            take(serverProbe.disconnected); take(clientProbe.disconnected);
            assertTrue(serverProbe.messages.isEmpty()); assertFalse(c.isActive());
        }
    }

    @Test void bothSingleFramesAndAggregatesRespectLimit() throws Exception {
        for (boolean fragmented : new boolean[] {false, true}) {
            Probe serverProbe = new Probe(), clientProbe = new Probe();
            AtomicReference<Channel> channel = new AtomicReference<>();
            try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game", 8).handler(serverProbe).build();
                 var client = NetworkClient.builder().webSocket(128).handler(clientProbe)
                         .pipeline(p -> channel.set(p.channel())).build()) {
                server.start(); client.connect(uri(server, "/game")); take(serverProbe.connected);
                if (fragmented) {
                    channel.get().writeAndFlush(new BinaryWebSocketFrame(false, 0, bytes("12345"))).sync();
                    channel.get().writeAndFlush(new ContinuationWebSocketFrame(true, 0, bytes("67890"))).sync();
                } else channel.get().writeAndFlush(new BinaryWebSocketFrame(bytes("1234567890"))).sync();
                take(serverProbe.disconnected); take(clientProbe.disconnected);
                assertTrue(serverProbe.messages.isEmpty());
            }
        }
    }

    @Test void exactPathAndImmutableHeaders() throws Exception {
        Probe probe = new Probe();
        BlockingQueue<String> headersSeen = new LinkedBlockingQueue<>();
        var headers = new DefaultHttpHeaders().set("X-Token", "original");
        var options = WebSocketConnectOptions.headers(headers);
        headers.set("X-Token", "changed");
        options.headers().set("X-Token", "also changed");
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").handler(new Probe()).pipeline(p ->
                p.addAfter("managame-http-aggregate", "headers", new ChannelInboundHandlerAdapter() {
                    public void channelRead(ChannelHandlerContext ctx, Object m) {
                        if (m instanceof HttpRequest request && request.headers().contains("X-Token"))
                            headersSeen.add(request.headers().get("X-Token"));
                        ctx.fireChannelRead(m);
                    }
                })).build();
             var client = NetworkClient.builder().webSocket().handler(probe).build()) {
            server.start();
            client.connect(uri(server, "/game?token=1"), options).close();
            assertEquals("original", take(headersSeen));
            for (String path : new String[] {"/game/", "/game/child", "/games", "/"})
                assertThrows(NetworkException.class, () -> client.connect(uri(server, path)));
            assertEquals(1, probe.connections.get());
            assertThrows(NetworkException.class, () -> client.connect(uri(server, "/game"),
                    WebSocketConnectOptions.of(new DefaultHttpHeaders(), "unsupported-subprotocol")));
        }
    }

    @Test void tlsFailureNeverCreatesConnectionAndWsIgnoresClientSsl() throws Exception {
        Probe probe = new Probe();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").sslContext(serverTls).handler(new Probe()).build();
             var client = NetworkClient.builder().webSocket().handler(probe).build()) {
            server.start();
            URI target = URI.create(uri(server, "/game").toString().replace("ws:", "wss:"));
            assertThrows(NetworkException.class, () -> client.connect(target), "Default TLS must reject an untrusted certificate");
            assertEquals(0, probe.connections.get()); assertEquals(0, probe.disconnects.get()); assertTrue(probe.errors.isEmpty());
        }
        try (var server = NetworkServer.builder().bindAddress(LOCAL).webSocket("/game").handler(new Probe()).build();
             var client = NetworkClient.builder().webSocket().sslContext(clientTls).handler(probe).build()) {
            server.start(); client.connect(uri(server, "/game")).close();
            assertEquals(1, probe.connections.get());
        }
    }

    @Test void invalidConfigurationsAndEndpointTypes() {
        assertThrows(IllegalStateException.class, () -> NetworkServer.builder().build());
        assertThrows(IllegalStateException.class, () -> NetworkClient.builder().build());
        for (String path : new String[] {"game", "/game?x", "/game#x", "/game/*", "//host/game", "ws://host/game"})
            assertThrows(IllegalArgumentException.class, () -> NetworkServer.builder().webSocket(path));
        assertThrows(IllegalArgumentException.class, () -> NetworkClient.builder().webSocket(0));
        assertThrows(NullPointerException.class, () -> NetworkClient.builder().pipeline(null));
        try (var tcp = NetworkClient.builder().handler(new Probe()).build();
             var ws = NetworkClient.builder().webSocket().handler(new Probe()).build()) {
            assertThrows(IllegalStateException.class, () -> tcp.connect(URI.create("ws://localhost/")));
            assertThrows(IllegalStateException.class, () -> ws.connect(LOCAL));
            for (String uri : new String[] {"http://localhost/", "ws:/path", "ws://localhost/#x", "ws://user@localhost/", "ws://localhost:99999/"})
                assertThrows(IllegalArgumentException.class, () -> ws.connect(URI.create(uri)));
        }
    }
}