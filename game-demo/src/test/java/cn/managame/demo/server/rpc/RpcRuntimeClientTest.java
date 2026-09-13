package cn.managame.demo.server.rpc;

import cn.managame.rpc.core.RpcCallback;
import cn.managame.rpc.core.RpcResult;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcResponse;
import cn.managame.runtime.execution.DomainLimits;
import cn.managame.runtime.execution.ExecutionDomain;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.execution.RouteDispatcher;
import cn.managame.runtime.execution.RouteTask;
import cn.managame.runtime.route.Route;

import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.serialization.MessageSerializer;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.rpc.RpcCommands;
import cn.managame.rpc.core.*;
import cn.managame.runtime.execution.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RpcRuntimeClientTest {
    public record UnregisteredReq(int amount) {}
    private static final Route PLAYER = new Route(PlayerRoute.class, 7);
    private GameRuntime runtime;
    private RpcRuntimeClient calls;
    private final AtomicReference<RpcCallback> response = new AtomicReference<>();
    private final AtomicReference<ByteBuf> requestBuffer = new AtomicReference<>();

    @BeforeEach void start() {
        runtime = runtime(DomainLimits.defaults());
        calls = new RpcRuntimeClient(runtime, (peer, command, body, options, callback) -> {
            requestBuffer.set(body);
            response.set(callback);
        }, this::stopTransportCalls);
    }

    @AfterEach void stop() {
        try { calls.close(); }
        finally { runtime.close(); }
    }

    @Test void successIsDecodedWhileBorrowedAndDeliveredOnTheTargetRoute() throws Exception {
        try (var blocked = block()) {
            var result = call();
            var route = result.thenApply(value -> HandlerContexts.current().route());
            assertEquals(0, requestBuffer.get().refCnt());
            respond(new GrantGoldRes(7, 90, 123));
            assertFalse(result.isDone());
            blocked.release();
            assertEquals(new GrantGoldRes(7, 90, 123), await(result));
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void businessFailureKeepsArgumentsAndUsesTheFailureRoute() throws Exception {
        try (var blocked = block()) {
            var result = call();
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            response.get().onResult(RpcResult.received(RpcResponse.error(1,
                    RpcError.fromCode(INVALID_GRANT), "101", "100")));
            assertFalse(result.isDone());
            blocked.release();
            var error = failure(result);
            assertEquals(INVALID_GRANT, error.errorCode());
            assertEquals(List.of("101", "100"), error.errorArgs());
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void localRpcResultPreservesCauseAcrossRuntimeDelivery() throws Exception {
        var cause = new IllegalStateException("rpc encoder");
        var result = call();
        response.get().onResult(RpcResult.failure(RpcError.INTERNAL_ERROR, cause));
        assertSame(cause, failure(result).getCause());
    }

    @Test void encodingFailureUsesTheRouteAndPreservesItsCause() throws Exception {
        var broken = new CommandBinding<>(2002, UnregisteredReq.class, GrantGoldRes.class);
        try (var blocked = block()) {
            var result = calls.call(20, broken, new UnregisteredReq(1), PLAYER, RpcOptions.route(7));
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            assertFalse(result.isDone());
            assertNull(response.get());
            blocked.release();
            var cause = assertInstanceOf(IllegalArgumentException.class, failure(result).getCause());
            assertTrue(cause.getMessage().contains("UnregisteredReq"));
            assertNotNull(cause.getCause());
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void synchronousTransportFailureReleasesRequestAndReturnsOnRoute() throws Exception {
        var cause = new IllegalStateException("transport failed");
        calls.close();
        calls = new RpcRuntimeClient(runtime, (peer, command, body, options, callback) -> {
            requestBuffer.set(body);
            throw cause;
        }, this::stopTransportCalls);
        try (var blocked = block()) {
            var result = call();
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            assertEquals(0, requestBuffer.get().refCnt());
            assertFalse(result.isDone());
            blocked.release();
            assertSame(cause, failure(result).getCause());
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void responseDecodeFailureDoesNotEscapeOrWaitForAnotherTimeout() throws Exception {
        try (var blocked = block()) {
            var result = call();
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            ByteBuf body = Unpooled.buffer(3).writeZero(3);
            try { response.get().onResult(RpcResult.success(new RpcResponse(1, 0, RpcMetadata.EMPTY, body))); }
            finally { body.release(); }
            blocked.release();
            assertEquals(RpcError.INTERNAL_ERROR.code(), failure(result).errorCode());
            assertInstanceOf(IllegalArgumentException.class, failure(result).getCause());
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void rpcTimeoutIsDeliveredOnceAndALateResponseCannotReplaceIt() throws Exception {
        try (var blocked = block()) {
            var result = call();
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            response.get().onResult(RpcResult.failure(RpcError.TIMEOUT));
            respond(new GrantGoldRes(7, 90, 123));
            assertFalse(result.isDone());
            blocked.release();
            assertEquals(RpcError.TIMEOUT.code(), failure(result).errorCode());
            assertEquals(PLAYER, await(route));
        }
    }

    @Test void fullCallbackCapacityFailsTheWaitHandleInsteadOfLeavingItPending() throws Exception {
        calls.close(); runtime.close();
        runtime = runtime(new DomainLimits(1, 1, 0, 0));
        calls = new RpcRuntimeClient(runtime, (peer, command, body, options, callback) -> response.set(callback), this::stopTransportCalls);
        try (var blocked = block()) {
            var result = call();
            respond(new GrantGoldRes(7, 90, 123));
            assertEquals(RpcError.OVERLOADED.code(), failure(result).errorCode());
        }
    }

    @Test void alreadyClosedRuntimeFailsDeliveryImmediately() throws Exception {
        var result = call();
        runtime.close();
        respond(new GrantGoldRes(7, 90, 123));
        assertEquals(RpcError.UNAVAILABLE.code(), failure(result).errorCode());
    }

    @Test void closingAdapterSettlesPendingCallsBeforeRuntimeShutdown() throws Exception {
        try (var blocked = block()) {
            var result = call();
            var route = result.handle((value, error) -> HandlerContexts.current().route());
            calls.close();
            assertFalse(result.isDone());
            respond(new GrantGoldRes(7, 90, 123));
            blocked.release();
            assertEquals(RpcError.UNAVAILABLE.code(), failure(result).errorCode());
            assertEquals(PLAYER, await(route));
            assertEquals(RpcError.UNAVAILABLE.code(), failure(call()).errorCode());
        }
    }

    @Test void adapterSupportsAnotherProtocolWithoutWalletSpecificResultWrappers() throws Exception {
        var binding = new CommandBinding<>(2001, String.class, Integer.class);
        var result = calls.call(20, binding, "hello", PLAYER, RpcOptions.route(7));
        ByteBuf body = MessageSerializer.serializeBuffer(123);
        try { response.get().onResult(RpcResult.success(new RpcResponse(1, 0, RpcMetadata.EMPTY, body))); }
        finally { body.release(); }
        assertEquals(123, await(result));
    }

    @Test void acceptedCallbackBackendFailureCompletesTheWaitingCall() throws Exception {
        calls.close();
        runtime.close();
        var cause = new IllegalStateException("continuation backend failed");
        var executor = Executors.newSingleThreadExecutor();
        RouteDispatcher backend = new RouteDispatcher() {
            public void dispatch(Route route, Runnable batch) { executor.execute(batch); }
            public void reschedule(Route route, Runnable batch) { throw cause; }
            public void shutdown() { executor.shutdown(); }
            public boolean awaitTermination(java.time.Duration timeout) throws InterruptedException {
                return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        };
        runtime = GameRuntime.builder().exceptionHandler(error -> {})
                .executionDomain(PlayerRoute.class, ExecutionDomain.custom("broken", () -> backend).tasksPerTurn(1).build()).build();
        calls = new RpcRuntimeClient(runtime, (peer, command, body, options, callback) -> response.set(callback), this::stopTransportCalls);
        try (var blocked = block()) {
            var result = call();
            respond(new GrantGoldRes(7, 90, 123));
            assertFalse(result.isDone(), "The callback was accepted behind the blocked task");
            blocked.release();
            var error = failure(result);
            assertEquals(RpcError.INTERNAL_ERROR.code(), error.errorCode());
            Throwable root = error;
            while (root.getCause() != null) root = root.getCause();
            assertSame(cause, root);
            respond(new GrantGoldRes(7, 1, 999));
            assertSame(error, failure(result));
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    private void stopTransportCalls() {
        RpcCallback callback = response.get();
        if (callback != null) callback.onResult(RpcResult.failure(RpcError.UNAVAILABLE));
    }

    private CompletableFuture<GrantGoldRes> call() {
        return calls.call(20, RpcCommands.GRANT, new GrantGoldReq(10), PLAYER, RpcOptions.route(7));
    }

    private void respond(GrantGoldRes wallet) {
        ByteBuf body = MessageSerializer.serializeBuffer(wallet);
        try { response.get().onResult(RpcResult.success(new RpcResponse(1, 0, RpcMetadata.EMPTY, body))); }
        finally { body.release(); }
    }

    private static GameRuntime runtime(DomainLimits limits) {
        return GameRuntime.builder().exceptionHandler(error -> {})
                .executionDomain(PlayerRoute.class, ExecutionDomain.platform("test").threads(1).limits(limits).build())
                .build();
    }

    private BlockedRoute block() throws Exception { return new BlockedRoute(); }
    private final class BlockedRoute implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final RouteTask task;
        BlockedRoute() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            task = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test route timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); task.get(3, TimeUnit.SECONDS); }
    }

    private static GameCallException failure(CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> await(future));
        return assertInstanceOf(GameCallException.class, error.getCause());
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
}
