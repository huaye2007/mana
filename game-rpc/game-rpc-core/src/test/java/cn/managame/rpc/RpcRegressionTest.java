package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcRegressionTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void slowEncodingOrSelectionDoesNotBlockTimeout(boolean slowEncoding) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var frames = new java.util.concurrent.CopyOnWriteArrayList<ByteBuf>();
        Runnable block =
                () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("not released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                };
        RpcCodec codec =
                new RpcCodec() {
                    final RpcCodec delegate =
                            new DefaultRpcCodec(
                                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, LIMITS);

                    public ByteBuf encode(RpcMessage message) {
                        if (slowEncoding && message instanceof RpcRequest) block.run();
                        var frame = delegate.encode(message);
                        if (!(message instanceof RpcHandshake)) frames.add(frame);
                        return frame;
                    }

                    public RpcMessage decode(ByteBuf input) {
                        return delegate.decode(input);
                    }
                };
        var a =
                fixture.builder(10, (c, m) -> {})
                        .codec(codec)
                        .selector(
                                (peer, key) -> {
                                    if (!slowEncoding) block.run();
                                    return peer.connection(0);
                                })
                        .build();
        fixture.nodes.add(a);
        var router = fixture.node(20, (c, m) -> fail("Expired request was sent"));
        fixture.connect(a, router, 1);
        var result = new CompletableFuture<RpcResult>();
        var callbacks = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                var call =
                        workers.submit(
                                () ->
                                        a.call(
                                                20,
                                                1,
                                                body("expired"),
                                                RpcOptions.builder()
                                                        .timeout(Duration.ofMillis(200))
                                                        .build(),
                                                r -> {
                                                    callbacks.incrementAndGet();
                                                    result.complete(r);
                                                }));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
                assertEquals(0, a.peer(20).pendingCount());
                assertFalse(call.isDone(), "Timeout must complete while preparation is blocked");
                release.countDown();
                call.get(2, TimeUnit.SECONDS);
                assertEquals(1, callbacks.get());
                assertEquals(1, frames.size());
                assertTrue(frames.stream().allMatch(frame -> frame.refCnt() == 0));
                assertEquals(
                        0,
                        fixture.network.transports.get(10).sent.stream()
                                .filter(frame -> frame[0] == 1)
                                .count());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void delayedTimerCannotAcceptLateSuccessOrRemoteError() throws Exception {
        var release = new CountDownLatch(1);
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            var a = fixture.builder(10, (c, m) -> {}).timer(timer).build();
            fixture.nodes.add(a);
            var request = new AtomicReference<RpcRequest>();
            var b = fixture.node(20, (c, m) -> request.set((RpcRequest) m));
            fixture.connect(a, b, 1);
            var entered = new CountDownLatch(1);
            timer.newTimeout(
                    ignored -> {
                        entered.countDown();
                        release.await();
                    },
                    0,
                    TimeUnit.MILLISECONDS);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (boolean remoteError : new boolean[] {false, true}) {
                    var result =
                            fixture.call(
                                    a,
                                    20,
                                    RpcOptions.builder().timeout(Duration.ofMillis(30)).build());
                    Thread.sleep(90);
                    assertFalse(result.isDone());
                    assertTrue(
                            remoteError
                                    ? fixture.replyError(request.get(), RpcError.NO_HANDLER)
                                    : fixture.reply(request.get(), body("late")));
                    assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
                    assertEquals(0, a.peer(20).pendingCount());
                }
            } finally {
                release.countDown();
                a.close();
            }
        } finally {
            timer.stop();
        }
    }

    @Test
    void decodingAcrossDeadlineCannotCompleteSuccessfully() throws Exception {
        var release = new CountDownLatch(1);
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            var codec =
                    new DefaultRpcCodec() {
                        public RpcMessage decode(ByteBuf input) {
                            var message = super.decode(input);
                            if (message instanceof RpcResponse) {
                                try {
                                    Thread.sleep(120);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            return message;
                        }
                    };
            var a = fixture.builder(10, (c, m) -> {}).codec(codec).timer(timer).build();
            fixture.nodes.add(a);
            var request = new AtomicReference<RpcRequest>();
            var b = fixture.node(20, (c, m) -> request.set((RpcRequest) m));
            fixture.connect(a, b, 1);
            var entered = new CountDownLatch(1);
            timer.newTimeout(
                    ignored -> {
                        entered.countDown();
                        release.await();
                    },
                    0,
                    TimeUnit.MILLISECONDS);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var result =
                        fixture.call(
                                a, 20, RpcOptions.builder().timeout(Duration.ofMillis(60)).build());
                assertTrue(fixture.reply(request.get(), body("slow decode")));
                assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
            } finally {
                release.countDown();
                a.close();
            }
        } finally {
            timer.stop();
        }
    }

    @Test
    void businessDispatchKeepsBlockingWorkOffTheTimeWheel() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var business = Executors.newSingleThreadExecutor()) {
            a.call(
                    20,
                    1,
                    body("x"),
                    RpcOptions.builder().timeout(Duration.ofMillis(20)).build(),
                    r ->
                            business.execute(
                                    () -> {
                                        entered.countDown();
                                        try {
                                            release.await();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var second =
                        fixture.call(
                                a, 20, RpcOptions.builder().timeout(Duration.ofMillis(30)).build());
                await(() -> a.peer(20).pendingCount() == 0);
                release.countDown();
                assertEquals(RpcError.TIMEOUT, second.get(2, TimeUnit.SECONDS).error());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void connectSuccessAndFailureCallbacksRunOutsideLifecycleLock() throws Exception {
        for (boolean success : new boolean[] {true, false}) {
            var release = new CountDownLatch(1);
            var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
            try {
                timer.newTimeout(
                        ignored -> {
                            release.await(5, TimeUnit.SECONDS);
                        },
                        0,
                        TimeUnit.MILLISECONDS);
                var a = fixture.builder(success ? 10 : 11, (c, m) -> {}).timer(timer).build();
                fixture.nodes.add(a);
                var b = fixture.node(success ? 20 : 21, (c, m) -> {});
                a.start();
                b.start();
                var field = RpcNode.class.getDeclaredField("connections");
                field.setAccessible(true);
                var lockField = RpcConnections.class.getDeclaredField("lock");
                lockField.setAccessible(true);
                var lock = lockField.get(field.get(a));
                var held = new CompletableFuture<Boolean>();
                a.connect(
                        b.nodeId(),
                        "127.0.0.1",
                        b.localAddress().getPort(),
                        new cn.managame.network.ConnectCallback() {
                            public void onSuccess(cn.managame.network.Connection connection) {
                                held.complete(Thread.holdsLock(lock));
                            }

                            public void onFailure(Throwable failure) {
                                held.complete(Thread.holdsLock(lock));
                            }
                        });
                try {
                    if (success) release.countDown();
                    else a.removePeer(b.nodeId());
                    assertFalse(held.get(2, TimeUnit.SECONDS));
                } finally {
                    release.countDown();
                    a.close();
                }
            } finally {
                timer.stop();
            }
        }
    }
}
