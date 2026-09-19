package cn.managame.network.netty.transport;

import cn.managame.network.testsupport.TestSignal;

import cn.managame.network.netty.connection.NettyAccess;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

@Timeout(30)
class BootstrapReuseTest {
    private static InetSocketAddress local() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    private static <T> T await(TestSignal<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    @Test
    void tcpSharesOneBootstrapAcrossConcurrentTargetsAndEventLoops() throws Exception {
        try (var resources = NetworkResources.builder().ioThreads(2).httpThreads(1).build()) {
            var server =
                    TcpNetworkServer.builder()
                            .resources(resources)
                            .listen("a", local())
                            .listen("b", local())
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            var client =
                    TcpNetworkClient.builder()
                            .resources(resources)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            try {
                server.start();
                assertNull(client.bootstrap);
                client.init();
                assertNotNull(client.bootstrap);
                var seen = new HashSet<String>();
                var eventLoops = new HashSet<io.netty.channel.EventLoop>();
                var first = client.bootstrap;
                client.init();
                for (int batch = 0; batch < 2; batch++) {
                    var results = new ArrayList<TestSignal<Connection>>();
                    var counts = new AtomicIntegerArray(64);
                    try (var callers = Executors.newFixedThreadPool(4)) {
                        for (int i = 0; i < 64; i++) {
                            int index = i;
                            int port =
                                    server.boundAddresses().get(i % 2 == 0 ? "a" : "b").getPort();
                            var result = new TestSignal<Connection>();
                            results.add(result);
                            callers.submit(
                                    () -> {
                                        try {
                                            client.connect(
                                                    "127.0.0.1",
                                                    port,
                                                    new ConnectCallback() {
                                                        public void onSuccess(Connection c) {
                                                            counts.incrementAndGet(index);
                                                            if (((InetSocketAddress)
                                                                                    c
                                                                                            .remoteAddress())
                                                                            .getPort()
                                                                    != port)
                                                                result.completeExceptionally(
                                                                        new AssertionError(
                                                                                "Wrong target"));
                                                            else result.complete(c);
                                                        }

                                                        public void onFailure(Throwable t) {
                                                            counts.incrementAndGet(index);
                                                            result.completeExceptionally(t);
                                                        }
                                                    });
                                        } catch (Throwable t) {
                                            result.completeExceptionally(t);
                                        }
                                    });
                        }
                    }
                    for (int i = 0; i < results.size(); i++) {
                        var connection = await(results.get(i));
                        assertTrue(seen.add(connection.id()));
                        eventLoops.add(NettyAccess.channel(connection).eventLoop());
                        assertEquals(1, counts.get(i));
                    }
                    assertSame(first, client.bootstrap);
                    assertEquals(2, eventLoops.size());
                    assertSame(resources.io(), first.config().group());
                    assertTrue(
                            first.config().attrs().isEmpty(),
                            "Shared Bootstrap must not contain per-connection state");
                    assertNull(first.config().remoteAddress());
                }
            } finally {
                client.destroy();
                server.stop();
            }
        }
    }

    @Test
    void wsSharesOneBootstrapWithoutMixingUrisOrCallbacks() throws Exception {
        try (var resources = NetworkResources.builder().ioThreads(2).httpThreads(1).build()) {
            var a =
                    WsNetworkServer.builder()
                            .resources(resources)
                            .listen("a", local())
                            .webSocketServer(b -> b.websocketPath("/a").checkStartsWith(true))
                            .handlerFactory(
                                    () ->
                                            (c, m) ->
                                                    c.write(
                                                            new TextWebSocketFrame(
                                                                    "a:"
                                                                            + ((TextWebSocketFrame)
                                                                                            m)
                                                                                    .text())))
                            .build();
            var b =
                    WsNetworkServer.builder()
                            .resources(resources)
                            .listen("b", local())
                            .webSocketServer(
                                    config -> config.websocketPath("/b").checkStartsWith(true))
                            .handlerFactory(
                                    () ->
                                            (c, m) ->
                                                    c.write(
                                                            new TextWebSocketFrame(
                                                                    "b:"
                                                                            + ((TextWebSocketFrame)
                                                                                            m)
                                                                                    .text())))
                            .build();
            var results = new ArrayList<TestSignal<String>>();
            for (int i = 0; i < 64; i++) results.add(new TestSignal<>());
            var client =
                    WsNetworkClient.builder()
                            .resources(resources)
                            .handlerFactory(
                                    () ->
                                            (c, m) -> {
                                                String text = ((TextWebSocketFrame) m).text();
                                                int index = Integer.parseInt(text.substring(2));
                                                results.get(index).complete(text);
                                            })
                            .build();
            try {
                a.start();
                b.start();
                assertNull(client.bootstrap);
                client.init();
                assertNotNull(client.bootstrap);
                var first = client.bootstrap;
                client.init();
                var eventLoops = ConcurrentHashMap.<io.netty.channel.EventLoop>newKeySet();
                var counts = new AtomicIntegerArray(64);
                try (var callers = Executors.newFixedThreadPool(4)) {
                    for (int i = 0; i < 64; i++) {
                        int index = i;
                        String name = i % 2 == 0 ? "a" : "b";
                        int port = (i % 2 == 0 ? a : b).boundAddresses().get(name).getPort();
                        var uri =
                                URI.create("ws://127.0.0.1:" + port + "/" + name + "?request=" + i);
                        callers.submit(
                                () -> {
                                    try {
                                        client.connect(
                                                uri,
                                                new ConnectCallback() {
                                                    public void onSuccess(Connection c) {
                                                        counts.incrementAndGet(index);
                                                        eventLoops.add(
                                                                NettyAccess.channel(c).eventLoop());
                                                        c.write(
                                                                new TextWebSocketFrame(
                                                                        Integer.toString(index)));
                                                    }

                                                    public void onFailure(Throwable t) {
                                                        counts.incrementAndGet(index);
                                                        results.get(index).completeExceptionally(t);
                                                    }
                                                });
                                    } catch (Throwable t) {
                                        results.get(index).completeExceptionally(t);
                                    }
                                });
                    }
                }
                for (int i = 0; i < 64; i++) {
                    assertEquals((i % 2 == 0 ? "a:" : "b:") + i, await(results.get(i)));
                    assertEquals(1, counts.get(i));
                }
                assertSame(first, client.bootstrap);
                assertSame(resources.io(), first.config().group());
                assertEquals(2, eventLoops.size());
                assertTrue(first.config().attrs().isEmpty());
                assertNull(first.config().remoteAddress());
            } finally {
                client.destroy();
                b.stop();
                a.stop();
            }
        }
    }
}
