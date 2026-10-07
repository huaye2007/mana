package cn.managame.network.netty;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.network.connection.*;
import cn.managame.network.connector.*;
import cn.managame.network.error.NetworkException;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkContractTest extends NetworkTestSupport {
    @ParameterizedTest @ValueSource(strings = {"tcp", "tls", "ws", "wss"})
    void roundTripAndOrderedWrites(String transport) throws Exception {
        boolean ws = transport.contains("ws"), tls = transport.equals("tls") || transport.equals("wss");
        Probe serverProbe = new Probe() {
            public void onMessage(Connection c, Object m) { super.onMessage(c, m); assertEquals(WriteStatus.ACCEPTED, c.write(m)); }
        };
        Probe clientProbe = new Probe();
        var sb = NetworkServer.builder().bindAddress(LOCAL).handler(serverProbe).pipeline(INTS);
        var cb = NetworkClient.builder().handler(clientProbe).pipeline(INTS);
        if (ws) { sb.webSocket("/game"); cb.webSocket(); }
        AtomicInteger port = new AtomicInteger();
        if (tls) {
            sb.pipeline(p -> p.addFirst("ssl", serverTls.newHandler(p.channel().alloc())));
            cb.pipeline(p -> p.addFirst("ssl", clientSsl(clientTls, p.channel(), "127.0.0.1", port.get())));
        }
        try (var server = sb.build(); var client = cb.build()) {
            assertNull(server.localAddress());
            server.start();
            port.set(((InetSocketAddress) server.localAddress()).getPort());
            assertThrows(IllegalStateException.class, server::start);
            URI uri = URI.create((tls ? "wss" : "ws") + "://127.0.0.1:"
                    + ((InetSocketAddress) server.localAddress()).getPort() + "/game?token=123");
            Connection c = ws ? client.connect(uri) : client.connect(server.localAddress());
            assertSame(c, take(clientProbe.connected));
            assertNotNull(take(serverProbe.connected));
            assertTrue(c.isActive());
            assertNotNull(c.localAddress()); assertNotNull(c.remoteAddress());
            AttributeKey<String> key = AttributeKey.valueOf("test.session");
            c.set(key, "session"); assertEquals("session", c.get(key));
            assertEquals("session", c.remove(key)); assertNull(c.get(key));
            for (int i = 0; i < 32; i++) assertEquals(WriteStatus.ACCEPTED, c.write(i));
            for (int i = 0; i < 32; i++) assertEquals(i, take(clientProbe.messages));
            assertTrue(clientProbe.events.isEmpty(), "Transport handshake events must be internal");
            c.close(); c.close();
            assertSame(c, take(clientProbe.disconnected));
            assertNotNull(take(serverProbe.disconnected));
            assertFalse(c.isActive());
            assertEquals(1, clientProbe.disconnects.get());
            assertTrue(clientProbe.errors.isEmpty(), clientProbe.errors.toString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"tcp", "tls", "ws", "wss"})
    void firstMessagesCanBeSentInsideOnConnected(String transport) throws Exception {
        boolean ws = transport.contains("ws"), tls = transport.equals("tls") || transport.equals("wss");
        Probe serverProbe = new Probe() {
            public void onConnected(Connection c) {
                super.onConnected(c);
                assertEquals(WriteStatus.ACCEPTED, c.write(11));
            }
        };
        List<String> order = new CopyOnWriteArrayList<>();
        Probe clientProbe = new Probe() {
            public void onConnected(Connection c) {
                order.add("connected");
                super.onConnected(c);
                assertEquals(WriteStatus.ACCEPTED, c.write(22));
            }
            public void onMessage(Connection c, Object message) {
                order.add("message");
                super.onMessage(c, message);
            }
        };
        var sb = NetworkServer.builder().bindAddress(LOCAL).handler(serverProbe).pipeline(INTS);
        var cb = NetworkClient.builder().handler(clientProbe).pipeline(INTS);
        if (ws) { sb.webSocket("/game"); cb.webSocket(); }
        AtomicInteger port = new AtomicInteger();
        if (tls) {
            sb.pipeline(p -> p.addFirst("ssl", serverTls.newHandler(p.channel().alloc())));
            cb.pipeline(p -> p.addFirst("ssl", clientSsl(clientTls, p.channel(), "127.0.0.1", port.get())));
        }
        try (var server = sb.build(); var client = cb.build()) {
            server.start();
            port.set(((InetSocketAddress) server.localAddress()).getPort());
            Promise<Connection> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            ConnectCallback callback = new ConnectCallback() {
                public void onSuccess(Connection c) { order.add("success"); result.trySuccess(c); }
                public void onFailure(Throwable cause) { result.tryFailure(cause); }
            };
            if (ws) client.connectAsync(URI.create((tls ? "wss" : "ws")
                    + "://127.0.0.1:" + port.get() + "/game"), callback);
            else client.connectAsync(server.localAddress(), callback);
            Connection connection = get(result);
            assertEquals(11, take(clientProbe.messages));
            assertEquals(22, take(serverProbe.messages));
            assertEquals("connected", order.getFirst());
            assertEquals(1, Collections.frequency(order, "success"));
            assertEquals(1, Collections.frequency(order, "message"));
            assertTrue(clientProbe.errors.isEmpty());
            assertTrue(serverProbe.errors.isEmpty());
            assertTrue(clientProbe.events.isEmpty());
            assertTrue(serverProbe.events.isEmpty());
            connection.close();
        }
    }

    @Test void ownershipBackpressureAndOutboundFailure() throws Exception {
        Probe probe = new Probe();
        EmbeddedChannel ch = embedded(probe, p -> p.addLast(new MessageToByteEncoder<String>() {
            protected void encode(ChannelHandlerContext ctx, String message, ByteBuf out) { throw new IllegalStateException("encode"); }
        }));
        Connection c = take(probe.connected);
        ByteBuf read = Unpooled.buffer().writeByte(1);
        ch.writeInbound(read);
        assertEquals(0, read.refCnt());
        ch.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        ByteBuf rejected = Unpooled.buffer().writeByte(2);
        assertEquals(WriteStatus.NOT_WRITABLE, c.write(rejected));
        assertEquals(1, rejected.refCnt()); rejected.release();
        ch.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        assertEquals(WriteStatus.ACCEPTED, c.write("fail"));
        assertInstanceOf(EncoderException.class, take(probe.errors));
        assertTrue(c.isActive(), "User decides whether an outbound failure closes the connection");
        c.close();
        ByteBuf inactive = Unpooled.buffer().writeByte(3);
        assertEquals(WriteStatus.INACTIVE, c.write(inactive));
        assertEquals(1, inactive.refCnt()); inactive.release();
        ch.finishAndReleaseAll();
    }

    @Test void handlerExceptionsEventsAndRetain() throws Exception {
        List<String> order = new ArrayList<>();
        AtomicReference<ByteBuf> retained = new AtomicReference<>();
        Probe probe = new Probe() {
            public void onConnected(Connection c) { super.onConnected(c); order.add("connected"); throw new IllegalStateException("connected"); }
            public void onMessage(Connection c, Object m) { retained.set(((ByteBuf) m).retain()); throw new IllegalStateException("message"); }
            public void onEvent(Connection c, Object event) { throw new IllegalStateException("event"); }
            public void onDisconnected(Connection c) { order.add("disconnected"); super.onDisconnected(c); throw new IllegalStateException("disconnected"); }
            public void onException(Connection c, Throwable cause) { super.onException(c, cause); order.add(cause.getMessage()); throw new IllegalStateException("error handler"); }
        };
        EmbeddedChannel ch = embedded(probe, p -> {});
        Connection c = take(probe.connected);
        ch.writeInbound(Unpooled.buffer().writeByte(1));
        assertEquals(1, retained.get().refCnt()); retained.get().release();
        ByteBuf event = Unpooled.buffer().writeByte(2);
        ch.pipeline().fireUserEventTriggered(event);
        assertEquals(1, event.refCnt()); event.release();
        assertTrue(c.isActive());
        var adapterContext = ch.pipeline().context(NetworkChannelInitializer.HANDLER);
        var adapter = (ConnectionHandlerAdapter) adapterContext.handler();
        c.close(); ch.runPendingTasks();
        int errors = probe.errors.size();
        adapter.userEventTriggered(adapterContext, new Object());
        adapter.exceptionCaught(adapterContext, new IllegalStateException("late"));
        assertEquals(errors, probe.errors.size());
        assertEquals(List.of("connected", "connected", "message", "event", "disconnected", "disconnected"), order);
        ch.finishAndReleaseAll();
    }

    @Test void asyncSuccessRunsAfterConnectedAndCallbackFailureIsNotConnectFailure() throws Exception {
        Probe probe = new Probe() {
            public void onConnected(Connection c) { super.onConnected(c); throw new IllegalStateException("connected"); }
        };
        Promise<Thread> callbackThread = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Channel> channel = new AtomicReference<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().handler(probe).pipeline(p -> channel.set(p.channel())).build()) {
            server.start();
            client.connectAsync(server.localAddress(), new ConnectCallback() {
                public void onSuccess(Connection c) {
                    assertEquals(1, probe.connections.get());
                    assertEquals(1, probe.errors.size());
                    callbackThread.trySuccess(Thread.currentThread());
                    throw new IllegalStateException("callback");
                }
                public void onFailure(Throwable cause) { failures.incrementAndGet(); callbackThread.tryFailure(cause); }
            });
            assertTrue(channel.get() == null || channel.get().isRegistered());
            Thread thread = get(callbackThread);
            assertTrue(channel.get().eventLoop().inEventLoop(thread));
            channel.get().eventLoop().submit(() -> {}).sync();
            assertEquals(0, failures.get()); assertEquals(1, probe.errors.size());
            take(probe.connected).close();
        }
    }

    @Test void externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls() throws Exception {
        var group = new NioEventLoopGroup(2);
        Probe serverProbe = new Probe(), clientProbe = new Probe();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).bossGroup(group).workerGroup(group).handler(serverProbe).build();
             var client = NetworkClient.builder().eventLoopGroup(group).handler(clientProbe).build()) {
            server.start();
            Connection c = client.connect(server.localAddress());
            Connection remote = take(serverProbe.connected);
            group.next().submit(() -> {
                assertThrows(IllegalStateException.class, () -> client.connect(server.localAddress()));
                assertThrows(IllegalStateException.class, client::close);
                assertThrows(IllegalStateException.class, server::close);
            }).sync();
            client.close(); server.close();
            assertFalse(group.isShuttingDown());
            assertTrue(c.isActive()); assertTrue(remote.isActive());
            assertEquals(WriteStatus.ACCEPTED, c.write(Unpooled.copiedBuffer("still alive", java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("still alive", take(serverProbe.messages));
            c.close(); remote.close();
            assertThrows(IllegalStateException.class, () -> client.connect(LOCAL));
            assertThrows(IllegalStateException.class, server::start);
        } finally { shutdown(group); }
    }

    @Test void snapshotsAndPerChannelPipelineOrder() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var builder = NetworkClient.builder().handler(new Probe())
                .pipeline(p -> calls.add("a")).pipeline(p -> calls.add("b"));
        try (var first = builder.build()) {
            builder.pipeline(p -> calls.add("c"));
            try (var second = builder.build();
                 var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build()) {
                server.start();
                first.connect(server.localAddress()).close();
                first.connect(server.localAddress()).close();
                second.connect(server.localAddress()).close();
                assertEquals(List.of("a", "b", "a", "b", "a", "b", "c"), calls);
            }
        }
    }

    @Test void bindAndPipelineFailuresCleanlyTerminate() throws Exception {
        Probe probe = new Probe();
        try (var running = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build()) {
            running.start();
            try (var failed = NetworkServer.builder().bindAddress(running.localAddress()).handler(probe).build()) {
                assertThrows(NetworkException.class, failed::start);
                assertThrows(IllegalStateException.class, failed::start);
            }
            try (var client = NetworkClient.builder().handler(probe).pipeline(p -> { throw new IllegalArgumentException("pipeline"); }).build()) {
                assertThrows(NetworkException.class, () -> client.connect(running.localAddress()));
                assertEquals(0, probe.connections.get()); assertEquals(0, probe.disconnects.get()); assertTrue(probe.errors.isEmpty());
            }
        }
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(probe)
                .pipeline(p -> { throw new IllegalArgumentException("server pipeline"); }).build();
             var client = NetworkClient.builder().handler(new Probe()).build()) {
            server.start();
            // TCP establishment may win before the peer closes its invalid pipeline.
            try { client.connect(server.localAddress()).close(); } catch (NetworkException expected) {}
            assertEquals(0, probe.connections.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"tcp", "tls", "ws", "wss"})
    void outboundFailuresDoNotAutoCloseEitherPeer(String transport) throws Exception {
        boolean ws = transport.contains("ws"), tls = transport.equals("tls") || transport.equals("wss");
        Probe left = new Probe(), right = new Probe();
        java.util.function.Consumer<ChannelPipeline> encoder = p -> p.addLast(new MessageToByteEncoder<String>() {
            protected void encode(ChannelHandlerContext ctx, String message, ByteBuf out) {
                throw new IllegalArgumentException("bad outgoing object");
            }
        });
        var sb = NetworkServer.builder().bindAddress(LOCAL).handler(left).pipeline(INTS).pipeline(encoder);
        var cb = NetworkClient.builder().handler(right).pipeline(INTS).pipeline(encoder);
        if (ws) { sb.webSocket("/game"); cb.webSocket(); }
        AtomicInteger port = new AtomicInteger();
        if (tls) {
            sb.pipeline(p -> p.addFirst("ssl", serverTls.newHandler(p.channel().alloc())));
            cb.pipeline(p -> p.addFirst("ssl", clientSsl(clientTls, p.channel(), "127.0.0.1", port.get())));
        }
        try (var server = sb.build(); var client = cb.build()) {
            server.start();
            port.set(((InetSocketAddress) server.localAddress()).getPort());
            URI uri = URI.create((tls ? "wss" : "ws") + "://127.0.0.1:" + port.get() + "/game");
            Connection c = ws ? client.connect(uri) : client.connect(server.localAddress());
            Connection s = take(left.connected);
            assertEquals(WriteStatus.ACCEPTED, c.write("bad"));
            assertInstanceOf(EncoderException.class, take(right.errors));
            assertEquals(WriteStatus.ACCEPTED, s.write("bad"));
            assertInstanceOf(EncoderException.class, take(left.errors));
            assertEquals(WriteStatus.ACCEPTED, c.write(42));
            assertEquals(42, take(left.messages));
            assertEquals(WriteStatus.ACCEPTED, s.write(43));
            assertEquals(43, take(right.messages));
            assertTrue(c.isActive()); assertTrue(s.isActive());
            c.close();
        }
    }
    static EmbeddedChannel embedded(Probe probe, java.util.function.Consumer<ChannelPipeline> configurer) {
        return new EmbeddedChannel(initializer(probe, configurer));
    }

    @Test void nativeHandlerAddedFailureRejectsClientEstablishment() throws Exception {
        Probe probe = new Probe();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().handler(probe).pipeline(p -> p.addLast(
                     new ChannelInboundHandlerAdapter() {
                         @Override public void handlerAdded(ChannelHandlerContext ctx) {
                             throw new IllegalArgumentException("handlerAdded");
                         }
                     })).build()) {
            server.start();
            Promise<Connection> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            client.connectAsync(server.localAddress(), new ConnectCallback() {
                public void onSuccess(Connection connection) { result.trySuccess(connection); }
                public void onFailure(Throwable cause) { result.tryFailure(cause); }
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> get(result));
            assertInstanceOf(NetworkException.class, failure.getCause());
            assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get());
        }
    }
}