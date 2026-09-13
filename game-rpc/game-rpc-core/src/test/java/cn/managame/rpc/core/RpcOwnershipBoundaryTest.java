package cn.managame.rpc.core;

import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcOwnershipBoundaryTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    private RpcPeer readyPeer(int capacity) throws Exception {
        var a =
                fixture.builder(10, (c, m) -> {})
                        .limits(new RpcLimits(4096, 1024, 4000, capacity))
                        .build();
        fixture.nodes.add(a);
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        return a.peer(20);
    }

    @Test
    void peerEnforcesCapacityUnderConcurrentRegistrationWithoutCallerLock() throws Exception {
        var peer = readyPeer(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int id = 1; id <= 64; id++) {
                int candidate = id;
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return peer.tryRegister(
                                                        candidate, Long.MAX_VALUE, result -> {})
                                                != null;
                                    } catch (RpcException failure) {
                                        assertSame(RpcException.OVERLOADED, failure);
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(8, accepted);
            assertEquals(8, peer.pendingCount());
        }
    }

    @Test
    void collisionPreservesExistingCallAndRetirementRejectsRegistration() throws Exception {
        var peer = readyPeer(8);
        var first = peer.tryRegister(1, Long.MAX_VALUE, result -> {});
        assertNull(peer.tryRegister(1, Long.MAX_VALUE, result -> fail("Collision callback")));
        assertSame(first, peer.pending(1));
        assertEquals(1, peer.pendingCount());
        fixture.nodes.getFirst().removePeer(20);
        assertTrue(first.isDone());
        assertEquals(0, peer.pendingCount());
        assertSame(
                RpcException.UNAVAILABLE,
                assertThrows(
                        RpcException.class,
                        () -> peer.tryRegister(2, Long.MAX_VALUE, result -> {})));
    }

    @Test
    void codecHandlerPropagatesFailureWithoutConstructingAnotherMessage() {
        var attempts = new AtomicInteger();
        var failure = new IllegalArgumentException("Injected encoding failure");
        var codec =
                new DefaultRpcCodec() {
                    @Override
                    public ByteBuf encode(RpcMessage message) {
                        attempts.incrementAndGet();
                        throw failure;
                    }
                };
        var handler = new RpcCodecHandler(codec, LIMITS);
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                handler.encode(
                                        new RpcResponse(1, 0, RpcMetadata.EMPTY, body("ok")))));
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void nodeAppliesSameSingleFallbackForSendAndReply(boolean useReply, boolean failFallback)
            throws Exception {
        var responseEncodes = new AtomicInteger();
        var codec =
                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                    @Override
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcResponse response) {
                            responseEncodes.incrementAndGet();
                            if (response.errorCode() == 0 || failFallback)
                                throw new IllegalArgumentException("Injected response failure");
                        }
                        return super.encode(message);
                    }
                };
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.builder(20, (c, m) -> {}).codec(codec).build();
        fixture.nodes.add(b);
        fixture.connect(a, b, 1);
        var transport = fixture.network.transports.get(20);
        transport.sent.clear();
        var response = new RpcResponse(1, 0, RpcMetadata.EMPTY, body("ok"));
        boolean accepted =
                useReply
                        ? b.reply(b.peer(10).connection(0), response)
                        : b.send(10, response, RpcOptions.DEFAULT);
        assertEquals(!failFallback, accepted);
        assertEquals(2, responseEncodes.get());
        assertEquals(1L, b.eventCounts().get("response-encode-failed"));
        assertEquals(failFallback ? 0 : 1, transport.sent.size());
        if (!failFallback) {
            var error = (RpcResponse) codec.decode(raw(transport.sent.getFirst()));
            assertEquals(1, error.requestId());
            assertEquals(RpcError.INTERNAL_ERROR.code(), error.errorCode());
            assertNull(error.body());
        }
    }

    @Test
    void steadyTrafficUsesConnectionIdentityWithoutReadingConnectionIds() throws Exception {
        var b =
                fixture.node(
                        20,
                        (c, m) -> {
                            var request = (RpcRequest) m;
                            if (request.requestId() > 0) fixture.reply(request, body("ok"));
                        });
        var a = fixture.node(10, (c, m) -> {});
        fixture.connect(a, b, 2);
        var connections = new ArrayList<Network.Conn>();
        for (int slot = 0; slot < 2; slot++) {
            connections.add((Network.Conn) a.peer(20).connection(slot));
            connections.add((Network.Conn) b.peer(10).connection(slot));
        }
        connections.forEach(c -> c.idReads.set(0));
        for (int i = 0; i < 32; i++) {
            var options = i % 2 == 0 ? RpcOptions.DEFAULT : RpcOptions.route(123);
            assertTrue(fixture.call(a, 20, options).get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(a.send(20, 1, body("notice"), options));
        }
        assertEquals(0, connections.stream().mapToInt(c -> c.idReads.get()).sum());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void handlerCannotBeReassignedToAnotherPhysicalConnection() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> fixture.reply((RpcMessage) m, body("ok")));
        fixture.connect(a, b, 1);
        var original = (Network.Conn) a.peer(20).connection(0);
        var other = fixture.network.new Conn(fixture.network.transports.get(10), true);
        try {
            assertThrows(IllegalStateException.class, () -> original.handler.onConnected(other));
            assertNull(a.peer(other));
            assertSame(original, a.peer(20).connection(0));
            assertTrue(
                    fixture.call(a, 20, RpcOptions.DEFAULT).get(2, TimeUnit.SECONDS).isSuccess());
        } finally {
            other.close();
        }
    }

    @Test
    void schedulingFailureAfterRegistrationCompletesAndRemovesCall() throws Exception {
        var timer = new io.netty.util.HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            var a = fixture.builder(10, (c, m) -> {}).timer(timer).build();
            fixture.nodes.add(a);
            var b = fixture.node(20, (c, m) -> fail("Failed call must not be sent"));
            fixture.connect(a, b, 1);
            timer.stop();
            var callbacks = new AtomicInteger();
            var result = new CompletableFuture<RpcResult>();
            a.call(
                    20,
                    1,
                    body("request"),
                    RpcOptions.DEFAULT,
                    value -> {
                        callbacks.incrementAndGet();
                        result.complete(value);
                    });
            assertEquals(RpcError.INTERNAL_ERROR, result.get(2, TimeUnit.SECONDS).error());
            assertEquals(0, a.peer(20).pendingCount());
            a.close();
            assertEquals(1, callbacks.get());
        } finally {
            timer.stop();
        }
    }
}
