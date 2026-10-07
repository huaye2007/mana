package cn.managame.network.netty;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
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
    @Test void externalClientCloseLeavesHandshakeToChannelOutcome() throws Exception {
        Probe probe = new Probe();
        var group = new NioEventLoopGroup(1);
        AtomicReference<Channel> channel = new AtomicReference<>();
        CountDownLatch active = new CountDownLatch(1);
        Promise<Throwable> outcome = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
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
                public void onSuccess(Connection c) { outcomes.incrementAndGet(); outcome.tryFailure(new AssertionError("unexpected success")); }
                public void onFailure(Throwable cause) { callbackThread.set(Thread.currentThread()); outcomes.incrementAndGet(); outcome.trySuccess(cause); }
            });
            assertTrue(active.await(5, TimeUnit.SECONDS));
            client.close();
            channel.get().eventLoop().submit(() -> {}).sync();
            assertTrue(channel.get().isActive(), "Borrowed-group channels are not closed by Client.close");
            assertFalse(outcome.isDone(), "Close does not traverse unfinished attempts");
            channel.get().close().sync();
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
        Promise<Boolean> interrupted = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
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
                try { client.connect(uri); interrupted.trySuccess(false); }
                catch (NetworkException expected) { interrupted.trySuccess(Thread.currentThread().isInterrupted()); }
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

    @Test void externalServerCloseKeepsChannelsAndRejectsLateHandshake() throws Exception {
        var boss = new NioEventLoopGroup(1);
        var worker = new NioEventLoopGroup(1);
        Probe serverProbe = new Probe();
        Probe rawProbe = new Probe();
        BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).bossGroup(boss).workerGroup(worker)
                     .webSocket("/game").handler(serverProbe).pipeline(p -> accepted.add(p.channel())).build();
             var client = NetworkClient.builder().webSocket().handler(new Probe()).build();
             var raw = NetworkClient.builder().handler(rawProbe).build()) {
            server.start();
            URI uri = URI.create("ws://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + "/game");
            Connection established = client.connect(uri);
            Channel establishedChannel = take(accepted);
            Connection remote = take(serverProbe.connected);
            Connection handshaking = raw.connect(server.localAddress());
            Channel handshakingChannel = take(accepted);
            handshakingChannel.eventLoop().submit(() -> {}).sync();

            server.close();
            worker.submit(() -> {}).sync();
            assertTrue(handshakingChannel.isActive(), "No registry traversal on Server.close");
            assertTrue(establishedChannel.isActive());
            assertEquals(WriteStatus.ACCEPTED, handshaking.write(io.netty.buffer.Unpooled.copiedBuffer(
                    "GET /game HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n",
                    java.nio.charset.StandardCharsets.US_ASCII)));
            assertTrue(handshakingChannel.closeFuture().await(5, TimeUnit.SECONDS));
            take(rawProbe.disconnected);
            worker.submit(() -> {}).sync();
            assertFalse(boss.isShuttingDown());
            assertFalse(worker.isShuttingDown());
            assertTrue(establishedChannel.isActive());
            assertTrue(established.isActive());
            assertEquals(1, serverProbe.connections.get());
            assertEquals(0, serverProbe.disconnects.get());
            assertTrue(serverProbe.errors.isEmpty());

            established.close();
            remote.close();
        } finally {
            shutdown(worker);
            shutdown(boss);
        }
    }

    @Test void serverCloseDuringInitializationPreventsLateDelivery() throws Exception {
        var boss = new NioEventLoopGroup(1);
        var worker = new NioEventLoopGroup(1);
        Probe probe = new Probe();
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch continueInitialization = new CountDownLatch(1);
        AtomicReference<Channel> accepted = new AtomicReference<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).bossGroup(boss).workerGroup(worker)
                     .handler(probe).pipeline(p -> {
                         accepted.set(p.channel());
                         initializing.countDown();
                         try {
                             if (!continueInitialization.await(5, TimeUnit.SECONDS))
                                 throw new IllegalStateException("Initialization was not released");
                         } catch (InterruptedException interrupted) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException(interrupted);
                         }
                     }).build();
             var client = NetworkClient.builder().handler(new Probe()).build()) {
            server.start();
            client.connect(server.localAddress());
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            try {
                server.close();
            } finally {
                continueInitialization.countDown();
            }
            accepted.get().closeFuture().sync();
            worker.submit(() -> {}).sync();
            assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get());
            assertTrue(probe.errors.isEmpty());
            assertFalse(worker.isShuttingDown());
        } finally {
            continueInitialization.countDown();
            shutdown(worker);
            shutdown(boss);
        }
    }

    @Test void externalClientRejectsReadinessAfterClosure() throws Exception {
        var group = new NioEventLoopGroup(1);
        Probe probe = new Probe();
        CountDownLatch initializing = new CountDownLatch(1), resume = new CountDownLatch(1);
        Promise<Throwable> failure = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        AtomicInteger outcomes = new AtomicInteger();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().eventLoopGroup(group).handler(probe).pipeline(p -> {
                 initializing.countDown();
                 try {
                     if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Initialization not released");
                 } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     throw new IllegalStateException(e);
                 }
             }).build()) {
            server.start();
            client.connectAsync(server.localAddress(), new ConnectCallback() {
                public void onSuccess(Connection c) {
                    outcomes.incrementAndGet(); c.close();
                    failure.tryFailure(new AssertionError("Late success"));
                }
                public void onFailure(Throwable cause) { outcomes.incrementAndGet(); failure.trySuccess(cause); }
            });
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            client.close();
            assertFalse(failure.isDone());
            assertFalse(group.isShuttingDown());
            resume.countDown();
            assertInstanceOf(IllegalStateException.class, get(failure));
            group.submit(() -> {}).sync();
            assertEquals(1, outcomes.get());
            assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get());
        } finally {
            resume.countDown();
            shutdown(group);
        }
    }


    @Test void interruptDuringOnConnectedClosesUndeliverableConnection() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1), resume = new CountDownLatch(1);
        Promise<Boolean> interrupted = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        Probe probe = new Probe() {
            public void onConnected(Connection connection) {
                super.onConnected(connection);
                callbackEntered.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Callback not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(new Probe()).build();
             var client = NetworkClient.builder().handler(probe).build()) {
            server.start();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try { client.connect(server.localAddress()); interrupted.trySuccess(false); }
                catch (NetworkException error) { interrupted.trySuccess(Thread.currentThread().isInterrupted()); }
                catch (Throwable error) { interrupted.tryFailure(error); }
            });
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                waiter.interrupt();
                assertTrue(get(interrupted), "Interrupt must reclaim even after success was claimed");
            } finally {
                resume.countDown();
                waiter.join(5000);
            }
            assertFalse(waiter.isAlive());
            Connection delivered = take(probe.connected);
            assertSame(delivered, take(probe.disconnected));
            assertFalse(delivered.isActive());
            assertEquals(1, probe.connections.get());
            assertEquals(1, probe.disconnects.get());
            assertTrue(probe.errors.isEmpty());
        } finally { resume.countDown(); }
    }

    @Test void terminatedExecutorReportsFailureOnCallingThread() {
        var group = new NioEventLoopGroup(1);
        AtomicInteger outcomes = new AtomicInteger();
        AtomicReference<Thread> notified = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Probe probe = new Probe();
        try (var client = NetworkClient.builder().eventLoopGroup(group).handler(probe).build()) {
            shutdown(group);
            client.connectAsync(LOCAL, new ConnectCallback() {
                public void onSuccess(Connection connection) { outcomes.addAndGet(100); connection.close(); }
                public void onFailure(Throwable cause) {
                    failure.set(cause);
                    outcomes.incrementAndGet();
                    notified.set(Thread.currentThread());
                }
            });
            assertEquals(1, outcomes.get());
            assertSame(Thread.currentThread(), notified.get());
            assertInstanceOf(NetworkException.class, failure.get());
            assertInstanceOf(RejectedExecutionException.class, failure.get().getCause());
            assertThrows(NetworkException.class, () -> client.connect(LOCAL));
            assertEquals(0, probe.connections.get());
        } finally { shutdown(group); }
    }

    @Test void interruptBeforeChannelCreationClosesLateChannel() throws Exception {
        var group = new NioEventLoopGroup(1);
        CountDownLatch creating = new CountDownLatch(1), resume = new CountDownLatch(1);
        Promise<Channel> created = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        Promise<Boolean> interrupted = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        Probe probe = new Probe();
        try (var client = NetworkClient.builder().eventLoopGroup(group).handler(probe).channelFactory(() -> {
            creating.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Factory not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            Channel channel = new io.netty.channel.socket.nio.NioSocketChannel();
            created.trySuccess(channel);
            return channel;
        }).build()) {
            Thread waiter = Thread.ofPlatform().start(() -> {
                try { client.connect(LOCAL); interrupted.trySuccess(false); }
                catch (NetworkException cause) { interrupted.trySuccess(Thread.currentThread().isInterrupted()); }
                catch (Throwable cause) { interrupted.tryFailure(cause); }
            });
            try {
                assertTrue(creating.await(5, TimeUnit.SECONDS));
                waiter.interrupt();
                assertTrue(get(interrupted));
            } finally { resume.countDown(); waiter.join(5000); }
            assertFalse(waiter.isAlive());
            Channel channel = get(created);
            assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
            group.submit(() -> {}).sync();
            assertFalse(channel.isOpen());
            assertEquals(0, probe.connections.get());
            assertEquals(0, probe.disconnects.get());
            assertTrue(probe.errors.isEmpty());
        } finally { resume.countDown(); shutdown(group); }
    }

}
