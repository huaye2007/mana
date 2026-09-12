package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import io.netty.buffer.*;
import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

class RpcBoundaryFixTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startingListenerCannotPromiseReadinessBeforeStartCompletes(boolean failStart)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new AtomicInteger();
        var startupFailure = new IllegalStateException("Injected start failure after bind");
        RpcNetworkProvider provider =
                config ->
                        new ForwardingTransport(fixture.network.create(config)) {
                            public void start(Listener listener) {
                                super.start(listener);
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("Not released");
                                } catch (InterruptedException e) {
                                    throw new AssertionError(e);
                                }
                                if (failStart) throw startupFailure;
                            }
                        };
        var receiver =
                fixture.builder(
                                10,
                                (c, m) -> {
                                    delivered.incrementAndGet();
                                    fixture.reply((RpcMessage) m, body("accepted"));
                                })
                        .provider(provider)
                        .build();
        fixture.nodes.add(receiver);
        var sender = fixture.node(20, (c, m) -> {});
        try (var workers = Executors.newSingleThreadExecutor()) {
            var start = workers.submit(receiver::start);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                sender.start();
                var connected =
                        connectResult(sender, 10, "127.0.0.1", receiver.localAddress().getPort());
                await(() -> sender.eventCounts().getOrDefault("connect-failed", 0L) > 0);
                assertFalse(receiver.isRunning());
                assertFalse(
                        connected.isDone(),
                        "STARTING must not complete the remote connect callback");
                assertFalse(sender.peer(10).isReady());
                assertNull(receiver.peer(20));
                assertTrue(fixture.network.transports.get(10).sent.isEmpty(), "No premature ACK");
                release.countDown();
                if (failStart) {
                    assertSame(
                            startupFailure,
                            assertThrows(
                                            ExecutionException.class,
                                            () -> start.get(2, TimeUnit.SECONDS))
                                    .getCause());
                    assertTrue(receiver.isClosed());
                    assertTrue(fixture.network.transports.get(10).closed);
                    sender.close();
                    assertThrows(
                            ExecutionException.class, () -> connected.get(2, TimeUnit.SECONDS));
                    assertEquals(0, delivered.get());
                } else {
                    start.get(2, TimeUnit.SECONDS);
                    connected.get(2, TimeUnit.SECONDS);
                    var result =
                            fixture.call(sender, 10, RpcOptions.DEFAULT).get(2, TimeUnit.SECONDS);
                    assertTrue(result.isSuccess());
                    assertEquals(
                            "accepted", result.value().body().toString(StandardCharsets.UTF_8));
                    assertEquals(1, delivered.get());
                }
            } finally {
                release.countDown();
            }
        }
    }

    enum Response {
        SUCCESS,
        ERROR,
        MALFORMED
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS,false,false",
        "SUCCESS,true,false",
        "SUCCESS,false,true",
        "ERROR,false,false",
        "ERROR,true,false",
        "MALFORMED,false,false",
        "MALFORMED,true,false"
    })
    void inlineResultLeavesSubmissionLockAndKeepsBodyAliveEvenIfSendThrows(
            Response response, boolean throwAfter, boolean callbackThrows) throws Exception {
        var wire = new DefaultRpcCodec(PooledByteBufAllocator.DEFAULT, LIMITS);
        var node = new AtomicReference<RpcNode>();
        var pending = new AtomicReference<RpcFuture>();
        var responseFrame = new AtomicReference<ByteBuf>();
        var sending = new AtomicBoolean();
        var callbacks = new AtomicInteger();
        RpcNetworkProvider provider =
                config ->
                        new ForwardingTransport(fixture.network.create(config)) {
                            public Submission write(Connection connection, ByteBuf frame) {
                                if (frame.getByte(frame.readerIndex()) != 1)
                                    return super.write(connection, frame);
                                int id = frame.getInt(frame.readerIndex() + 5);
                                pending.set(node.get().peer(20).pending(id));
                                var encoded =
                                        wire.encode(
                                                new RpcResponse(
                                                        id,
                                                        response == Response.ERROR
                                                                ? RpcError.UNAVAILABLE.code()
                                                                : 0,
                                                        RpcMetadata.EMPTY,
                                                        response == Response.ERROR
                                                                ? null
                                                                : body("inline")));
                                responseFrame.set(encoded);
                                if (response == Response.MALFORMED) encoded.setInt(5, -1);
                                sending.set(true);
                                try {
                                    var c = (Network.Conn) connection;
                                    c.handler.onMessage(c, encoded);
                                    assertEquals(
                                            1,
                                            callbacks.get(),
                                            "Inline responses keep their original delivery order");
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                } finally {
                                    encoded.release();
                                    sending.set(false);
                                }
                                if (throwAfter)
                                    throw new IllegalStateException(
                                            "Injected failure after response");
                                return Submission.ACCEPTED;
                            }
                        };
        var a = fixture.builder(10, (c, m) -> {}).provider(provider).build();
        fixture.nodes.add(a);
        node.set(a);
        var b = fixture.node(20, (c, m) -> fail("Request is answered by the injected transport"));
        fixture.connect(a, b, 1);
        var retained = new AtomicReference<ByteBuf>();
        var callbackFailure = new AtomicReference<Throwable>();
        var caller = Thread.currentThread();
        try {
            a.call(
                    20,
                    1,
                    body("request"),
                    RpcOptions.DEFAULT,
                    result -> {
                        callbacks.incrementAndGet();
                        try {
                            assertSame(caller, Thread.currentThread());
                            assertTrue(
                                    sending.get(),
                                    "Preserve synchronous delivery on the notifying thread");
                            assertFalse(Thread.holdsLock(pending.get()));
                            assertEquals(0, a.peer(20).pendingCount());
                            if (response == Response.SUCCESS) {
                                assertTrue(result.isSuccess());
                                assertEquals(
                                        "inline",
                                        result.value().body().toString(StandardCharsets.UTF_8));
                                retained.set(result.value().body().retainedDuplicate());
                                a.removePeer(
                                        20); // User code can reenter lifecycle management after
                                // send exits.
                            } else
                                assertEquals(
                                        response == Response.ERROR
                                                ? RpcError.UNAVAILABLE
                                                : RpcError.PROTOCOL_ERROR,
                                        result.error());
                        } catch (Throwable failure) {
                            callbackFailure.set(failure);
                        }
                        if (callbackThrows)
                            throw new IllegalStateException("Injected user callback failure");
                    });
            assertNull(callbackFailure.get());
            assertEquals(1, callbacks.get());
            assertEquals(
                    callbackThrows ? 1L : 0L, a.eventCounts().getOrDefault("callback-failed", 0L));
            assertTrue(pending.get().isDone());
            assertEquals(response == Response.SUCCESS ? 1 : 0, responseFrame.get().refCnt());
            a.close();
            assertEquals(1, callbacks.get());
        } finally {
            if (retained.get() != null) retained.get().release();
        }
        assertEquals(0, responseFrame.get().refCnt());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void constructionFailureClosesCreatedTransportAndPreservesBorrowedTimer(
            boolean nullAllocator, boolean closeThrows) {
        var original = new IllegalStateException("Injected allocator failure");
        var cleanup = new IllegalArgumentException("Injected close failure");
        var closed = new AtomicInteger();
        var started = new AtomicInteger();
        var timer = new HashedWheelTimer();
        try {
            RpcNetworkProvider provider =
                    config ->
                            new ForwardingTransport(fixture.network.create(config)) {
                                public ByteBufAllocator allocator() {
                                    if (nullAllocator) return null;
                                    throw original;
                                }

                                public void start(Listener listener) {
                                    started.incrementAndGet();
                                }

                                public void close() {
                                    closed.incrementAndGet();
                                    super.close();
                                    if (closeThrows) throw cleanup;
                                }
                            };
            var failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    fixture.builder(10, (c, m) -> {})
                                            .timer(timer)
                                            .provider(provider)
                                            .build());
            if (nullAllocator) assertInstanceOf(NullPointerException.class, failure);
            else assertSame(original, failure);
            assertArrayEquals(
                    closeThrows ? new Throwable[] {cleanup} : new Throwable[0],
                    failure.getSuppressed());
            assertEquals(1, closed.get());
            assertEquals(0, started.get());
            assertTrue(fixture.network.transports.get(10).closed);
            var stillUsable = assertDoesNotThrow(() -> timer.newTimeout(t -> {}, 1, TimeUnit.DAYS));
            stillUsable.cancel();
        } finally {
            timer.stop();
        }
    }

    @Test
    void inFlightTransportDoesNotBlockRemovalAndNotifiesOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var encoded = new AtomicReference<ByteBuf>();
        RpcNetworkProvider provider =
                config ->
                        new ForwardingTransport(fixture.network.create(config)) {
                            public Submission write(Connection c, ByteBuf frame) {
                                if (frame.getByte(frame.readerIndex()) == 1) {
                                    encoded.set(frame);
                                    entered.countDown();
                                    try {
                                        if (!release.await(5, TimeUnit.SECONDS))
                                            throw new AssertionError("Not released");
                                    } catch (InterruptedException e) {
                                        throw new AssertionError(e);
                                    }
                                }
                                return super.write(c, frame);
                            }
                        };
        var a =
                fixture.builder(10, (c, m) -> {})
                        .provider(provider)
                        .codec(new DefaultRpcCodec(PooledByteBufAllocator.DEFAULT, LIMITS))
                        .build();
        fixture.nodes.add(a);
        var b = fixture.node(20, (c, m) -> fail("Closed connection must reject the pending write"));
        fixture.connect(a, b, 1);
        var result = new CompletableFuture<RpcResult>();
        var callbacks = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var call =
                    workers.submit(
                            () ->
                                    a.call(
                                            20,
                                            1,
                                            body("request"),
                                            RpcOptions.DEFAULT,
                                            value -> {
                                                callbacks.incrementAndGet();
                                                result.complete(value);
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                workers.submit(() -> a.removePeer(20)).get(2, TimeUnit.SECONDS);
                assertEquals(RpcError.UNAVAILABLE, result.getNow(null).error());
                assertFalse(call.isDone());
                release.countDown();
                call.get(2, TimeUnit.SECONDS);
                assertEquals(1, callbacks.get());
                assertEquals(0, encoded.get().refCnt());
            } finally {
                release.countDown();
            }
        }
    }

    private static class ForwardingTransport implements RpcTransport {
        final RpcTransport delegate;

        ForwardingTransport(RpcTransport delegate) {
            this.delegate = delegate;
        }

        public ByteBufAllocator allocator() {
            return delegate.allocator();
        }

        public void start(Listener listener) {
            delegate.start(listener);
        }

        public void connect(InetSocketAddress address, ConnectCallback callback) {
            delegate.connect(address, callback);
        }

        public InetSocketAddress localAddress() {
            return delegate.localAddress();
        }

        public Submission write(Connection connection, ByteBuf frame) {
            return delegate.write(connection, frame);
        }

        public void close() {
            delegate.close();
        }
    }
}
