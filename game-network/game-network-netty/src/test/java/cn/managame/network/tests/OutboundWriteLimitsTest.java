package cn.managame.network.tests;

import cn.managame.network.Connection;
import cn.managame.network.ConnectionType;
import cn.managame.network.NetworkHandler;
import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.network.netty.connection.NettyConnection;
import cn.managame.network.netty.connection.OutboundWriteLimits;
import cn.managame.network.netty.transport.NetworkResources;
import cn.managame.network.netty.transport.TcpNetworkServer;
import cn.managame.network.testsupport.TestSignal;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class OutboundWriteLimitsTest {
    @Test
    void byteBudgetRetainsRejectedOwnershipAndRecyclesOnSuccessAndFailure() {
        var messages = new ArrayList<ByteBuf>();
        var writes = new ArrayList<ChannelPromise>();
        var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                messages.add((ByteBuf) message);
                writes.add(promise);
            }
        });
        var connection = new NettyConnection(channel, ConnectionType.TCP, new OutboundWriteLimits(10, 8));
        var callbacks = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var rejectionCallbacks = new AtomicInteger();
        ByteBuf first = Unpooled.buffer(4).writeZero(4);
        ByteBuf second = Unpooled.buffer(4).writeZero(4);
        ByteBuf rejected = Unpooled.buffer(1).writeZero(1);
        try {
            assertTrue(connection.write(first, cause -> callbacks.incrementAndGet()));
            assertTrue(connection.write(second, cause -> {
                failure.set(cause);
                callbacks.incrementAndGet();
            }));
            assertFalse(connection.isWritable());
            assertFalse(connection.write(rejected, cause -> rejectionCallbacks.incrementAndGet()));
            assertEquals(1, rejected.refCnt());
            assertEquals(0, rejectionCallbacks.get());
            first.release();
            writes.get(0).setSuccess();
            assertTrue(connection.isWritable());
            assertTrue(connection.write(rejected));
            var transportFailure = new IOException("peer closed");
            second.release();
            writes.get(1).setFailure(transportFailure);
            assertSame(transportFailure, failure.get());
            assertEquals(2, callbacks.get());
            rejected.release();
            writes.get(2).setSuccess();
            assertTrue(connection.isWritable());
        } finally {
            for (ByteBuf message : messages) if (message.refCnt() > 0) message.release();
            if (rejected.refCnt() > 0) rejected.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void concurrentCallersReserveBeforeBlockedEventLoopAndReleaseAfterCompletion() throws Exception {
        int limit = 16;
        int attempts = 128;
        var acceptedConnection = new TestSignal<Connection>();
        var blocked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var callbacks = new CountDownLatch(limit);
        var callbackCount = new AtomicInteger();
        var callbackFailure = new AtomicReference<Throwable>();
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var messages = new ByteBuf[attempts];
        try (var resources = NetworkResources.builder().ioThreads(1).build()) {
            var server = TcpNetworkServer.builder().resources(resources).listen("127.0.0.1", 0)
                    .outboundWriteLimits(new OutboundWriteLimits(limit, 1024))
                    .handlerFactory(() -> new NetworkHandler() {
                        public void onConnected(Connection connection) { acceptedConnection.complete(connection); }
                        public void onMessage(Connection connection, Object message) {}
                    }).build();
            try {
                server.start();
                int port = server.boundAddresses().values().iterator().next().getPort();
                try (var peer = new Socket("127.0.0.1", port)) {
                    Connection connection = acceptedConnection.get(5, TimeUnit.SECONDS);
                    var channel = NettyAccess.channel(connection);
                    channel.eventLoop().execute(() -> {
                        blocked.countDown();
                        try {
                            if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer test stalled");
                        } catch (InterruptedException interruption) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    assertTrue(blocked.await(5, TimeUnit.SECONDS));
                    try (var callers = Executors.newFixedThreadPool(8)) {
                        var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                        for (int i = 0; i < attempts; i++) {
                            int index = i;
                            jobs.add(callers.submit(() -> {
                                ByteBuf message = Unpooled.buffer(4).writeInt(index);
                                messages[index] = message;
                                if (connection.write(message, failure -> {
                                    if (failure != null) callbackFailure.set(failure);
                                    callbackCount.incrementAndGet();
                                    callbacks.countDown();
                                })) {
                                    accepted.incrementAndGet();
                                } else {
                                    assertEquals(1, message.refCnt());
                                    message.release();
                                    rejected.incrementAndGet();
                                }
                            }));
                        }
                        for (var job : jobs) job.get(5, TimeUnit.SECONDS);
                    }
                    assertEquals(limit, accepted.get());
                    assertEquals(attempts - limit, rejected.get());
                    assertEquals(0, callbackCount.get());
                    assertFalse(connection.isWritable());
                    resume.countDown();
                    assertTrue(callbacks.await(5, TimeUnit.SECONDS));
                    assertEquals(limit, callbackCount.get());
                    assertNull(callbackFailure.get());
                    assertTrue(connection.isWritable());
                    assertTrue(java.util.Arrays.stream(messages).allMatch(message -> message.refCnt() == 0));
                    ByteBuf next = Unpooled.buffer(1).writeByte(1);
                    var nextCompleted = new TestSignal<Throwable>();
                    assertTrue(connection.write(next, nextCompleted::complete));
                    assertNull(nextCompleted.get(5, TimeUnit.SECONDS));
                    assertEquals(0, next.refCnt());
                }
            } finally {
                resume.countDown();
                server.stop();
            }
        }
    }

    @Test
    void invalidLimitsFailBeforeResourcesAreCreated() {
        assertThrows(IllegalArgumentException.class, () -> new OutboundWriteLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboundWriteLimits(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboundWriteLimits(-1, 1));
    }
}
