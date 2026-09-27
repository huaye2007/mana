package cn.managame.network.netty;

import cn.managame.network.connection.*;
import cn.managame.network.connector.*;
import cn.managame.network.error.NetworkException;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectRaceTest extends NetworkTestSupport {
    @Test void closeCancelsPendingHandshakeExactlyOnceOnEventLoop() throws Exception {
        Probe probe = new Probe();
        var group = new NioEventLoopGroup(1);
        AtomicReference<Channel> channel = new AtomicReference<>();
        CountDownLatch active = new CountDownLatch(1);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        AtomicInteger outcomes = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        try (var blackhole = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().eventLoopGroup(group).webSocket().handler(probe)
                     .pipeline(p -> {
                         channel.set(p.channel());
                         p.addLast(new ChannelInboundHandlerAdapter() {
                             public void channelActive(ChannelHandlerContext ctx) { active.countDown(); ctx.fireChannelActive(); }
                         });
                     }).build()) {
            blackhole.start();
            URI uri = URI.create("ws://127.0.0.1:" + ((InetSocketAddress) blackhole.localAddress()).getPort() + "/game");
            client.connectAsync(uri, new ConnectCallback() {
                public void onSuccess(Connection c) { outcomes.incrementAndGet(); outcome.completeExceptionally(new AssertionError("unexpected success")); }
                public void onFailure(Throwable cause) { callbackThread.set(Thread.currentThread()); outcomes.incrementAndGet(); outcome.complete(cause); }
            });
            assertTrue(active.await(5, TimeUnit.SECONDS));
            client.close();
            assertInstanceOf(IllegalStateException.class, get(outcome));
            channel.get().closeFuture().sync();
            channel.get().eventLoop().submit(() -> {}).sync();
            assertTrue(channel.get().eventLoop().inEventLoop(callbackThread.get()));
            assertEquals(1, outcomes.get()); assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get()); assertTrue(probe.errors.isEmpty());
            assertFalse(group.isShuttingDown());
        } finally { shutdown(group); }
    }

    @Test void interruptCancelsHandshakeAndRestoresFlag() throws Exception {
        Probe probe = new Probe();
        AtomicReference<Channel> channel = new AtomicReference<>();
        CountDownLatch active = new CountDownLatch(1);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        try (var blackhole = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().webSocket().handler(probe).pipeline(p -> {
                 channel.set(p.channel());
                 p.addLast(new ChannelInboundHandlerAdapter() {
                     public void channelActive(ChannelHandlerContext ctx) { active.countDown(); ctx.fireChannelActive(); }
                 });
             }).build()) {
            blackhole.start();
            URI uri = URI.create("ws://127.0.0.1:" + ((InetSocketAddress) blackhole.localAddress()).getPort() + "/");
            Thread thread = Thread.ofPlatform().start(() -> {
                try { client.connect(uri); interrupted.complete(false); }
                catch (NetworkException expected) { interrupted.complete(Thread.currentThread().isInterrupted()); }
            });
            assertTrue(active.await(5, TimeUnit.SECONDS));
            thread.interrupt(); assertTrue(get(interrupted)); thread.join(5000);
            channel.get().closeFuture().sync();
            assertEquals(0, probe.connections.get()); assertEquals(0, probe.disconnects.get());
        }
    }

    @Test void successAndCloseRaceHasOneOutcomePerAttempt() throws Exception {
        var group = new NioEventLoopGroup(2);
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build()) {
            server.start();
            for (int round = 0; round < 12; round++) {
                var client = NetworkClient.builder().eventLoopGroup(group).handler(new Probe()).build();
                AtomicInteger outcomes = new AtomicInteger();
                CountDownLatch complete = new CountDownLatch(12);
                ConnectCallback callback = new ConnectCallback() {
                    public void onSuccess(Connection c) { outcomes.incrementAndGet(); c.close(); complete.countDown(); }
                    public void onFailure(Throwable cause) { outcomes.incrementAndGet(); complete.countDown(); }
                };
                Thread connections = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 12; i++) client.connectAsync(server.localAddress(), callback);
                });
                client.close();
                connections.join(5000); assertFalse(connections.isAlive());
                assertTrue(complete.await(5, TimeUnit.SECONDS));
                for (var loop : group) loop.submit(() -> {}).sync();
                assertEquals(12, outcomes.get());
            }
        } finally { shutdown(group); }
    }

    @Test void onConnectedCloseStillReportsSuccessfulConnect() throws Exception {
        Probe probe = new Probe() {
            public void onConnected(Connection c) { super.onConnected(c); c.close(); }
        };
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().handler(probe).build()) {
            server.start();
            Connection c = client.connect(server.localAddress());
            assertNotNull(c); assertSame(c, take(probe.connected)); assertSame(c, take(probe.disconnected));
            assertEquals(1, probe.connections.get()); assertEquals(1, probe.disconnects.get());
        }
    }
}