package cn.managame.runtime;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.core.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.DefaultClientHandlerContext;
import cn.managame.runtime.context.DefaultRpcHandlerContext;
import cn.managame.runtime.context.RpcHandlerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client ingress response contract. A Handler answers in one of two ways: it returns the registered
 * response and Runtime frames and writes it, or it returns void and writes on the context's connection
 * itself. There is no framework completion object in a Handler signature.
 */
class ClientResponseTest {
    record Ping(long userId) {}
    record Pong(String text) {}
    record Join(long userId) {}
    record Joined(long guildId) {}
    /** What the codec framed; it stands in for one transport message. */
    record Framed(int command, int requestId, int errorCode, Object result) {}

    static final class Codec implements ClientResultCodec {
        volatile RuntimeException encodeFailure, errorFailure;
        @Override public Object encode(ClientHandlerContext context, Object result) {
            if (encodeFailure != null) throw encodeFailure;
            return new Framed(context.command(), context.requestId(), 0, result);
        }
        @Override public Object encodeError(ClientHandlerContext context, int errorCode) {
            if (errorFailure != null) throw errorFailure;
            return new Framed(context.command(), context.requestId(), errorCode, null);
        }
    }

    @Handler(domain = 1, routeKey = "userId")
    static class Handlers {
        volatile GameRuntime runtime;
        volatile RuntimeException failure;
        volatile Pong answer = new Pong("ok");

        /** Mode one: the return value is the response. */
        @HandlerMethod public Pong ping(ClientHandlerContext context, Ping message) {
            if (failure != null) throw failure;
            return answer;
        }

        /** Mode two: answer by hand, after another Route returns, using the context's own correlation. */
        @HandlerMethod public void join(ClientHandlerContext context, Join message) {
            runtime.call(2, 77L, () -> new Joined(77L), new RouteCallback<Joined>() {
                @Override public void onSuccess(Joined joined) {
                    assertSame(context, Contexts.current());
                    context.connection().write(new Framed(context.command(), context.requestId(), 0, joined));
                }
                @Override public void onFail(int errorCode) {
                    context.connection().write(new Framed(context.command(), context.requestId(), errorCode, null));
                }
            });
        }
    }

    static Connection connection(List<Object> writes, WriteStatus status) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "write" -> { writes.add(args[0]); yield status; }
                    case "isActive", "isWritable" -> status == WriteStatus.ACCEPTED;
                    default -> throw new AssertionError("Unexpected Connection operation: " + method.getName());
                });
    }

    static GameRuntimeBuilder builder(Object handler, Codec codec, List<RuntimeError> errors) {
        return withoutCodec(handler).clientResultCodec(codec).errorHandler(errors::add);
    }

    /** The same registry and Routes, with no client response codec configured. */
    static GameRuntimeBuilder withoutCodec(Object handler) {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "one"), RouteDomain.of(2, "two")))
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, t) -> {
                    t.run(); return RouteExecuteStatus.ACCEPTED;
                }, 1, 2)))
                .protocols(ProtocolRegistry.of(
                        ProtocolDescriptor.request(1, Ping.class, Pong.class),
                        ProtocolDescriptor.response(11, Pong.class),
                        ProtocolDescriptor.request(2, Join.class, Joined.class),
                        ProtocolDescriptor.response(12, Joined.class)))
                .handlers(List.of(handler));
    }

    private static DefaultClientHandlerContext request(Object message, Connection connection, int command, int requestId) {
        return new DefaultClientHandlerContext(1, 42L, 0, 0, Metadata.empty(), message, connection, command, requestId);
    }

    @Test void aReturnedResponseIsFramedWithTheRequestCorrelationAndWrittenOnItsConnection() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        try (var runtime = builder(handler, new Codec(), errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 7));
            assertEquals(List.of(new Framed(1, 7, 0, new Pong("ok"))), writes);
            assertTrue(errors.isEmpty());
        }
    }

    @Test void aVoidHandlerAnswersThroughTheConnectionItselfAndRuntimeSendsNothing() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        var codec = new Codec();
        // The codec throws if Runtime ever frames anything for this void Handler.
        codec.encodeFailure = new IllegalStateException("Runtime must not frame a void Handler's result");
        codec.errorFailure = new IllegalStateException("Runtime must not frame an error for a void Handler");
        try (var runtime = builder(handler, codec, errors).build()) {
            handler.runtime = runtime;
            runtime.dispatch(request(new Join(42L), connection(writes, WriteStatus.ACCEPTED), 2, 5));
            // Exactly one write, produced by the Handler's own callback under the request's correlation.
            assertEquals(List.of(new Framed(2, 5, 0, new Joined(77L))), writes);
            assertTrue(errors.isEmpty());
        }
    }

    @Test void theFactoryEntryCarriesCorrelationAndMustPreserveIt() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        var connection = connection(writes, WriteStatus.ACCEPTED);
        var drop = new AtomicBoolean();
        try (var runtime = builder(handler, new Codec(), errors)
                .handlerContextFactory((domain, command, requestId, peer, metadata, message) ->
                        new DefaultClientHandlerContext(domain, 42L, 0, 0, metadata, message, peer,
                                command, drop.get() ? 0 : requestId))
                .build()) {
            runtime.dispatch(connection, 1, 9, Metadata.empty(), new Ping(42L));
            assertEquals(List.of(new Framed(1, 9, 0, new Pong("ok"))), writes);
            drop.set(true);
            assertEquals(FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH,
                    assertThrows(RuntimeDispatchException.class,
                            () -> runtime.dispatch(connection, 1, 9, Metadata.empty(), new Ping(42L))).errorCode());
            assertEquals(1, writes.size()); assertTrue(errors.isEmpty());
        }
    }

    @Test void aHandlerExceptionIsReportedAndAnsweredWithAFrameworkCodeOnly() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        handler.failure = new IllegalStateException("business detail that must not reach the peer");
        try (var runtime = builder(handler, new Codec(), errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 7));
            assertEquals(List.of(new Framed(1, 7, FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR, null)), writes);
            assertEquals(1, errors.size());
            assertSame(handler.failure, errors.getFirst().cause());
            assertEquals(FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR, errors.getFirst().errorCode());
        }
    }

    @Test void aNullResultAnswersNothingWithoutReporting() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        handler.answer = null;
        try (var runtime = builder(handler, new Codec(), errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 7));
            assertTrue(writes.isEmpty()); assertTrue(errors.isEmpty());
        }
    }

    @Test void answeringWithoutCorrelationOrConnectionWritesNothingAndIsReported() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        try (var runtime = builder(handler, new Codec(), errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 0));
            runtime.dispatch(request(new Ping(42L), null, 1, 7));
            assertTrue(writes.isEmpty());
            assertEquals(2, errors.size());
            errors.forEach(error -> assertEquals(FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR, error.errorCode()));
        }
    }

    @Test void aRefusedWriteReleasesWhatTheCodecFramedAndAnAcceptedOneDoesNot() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        var framed = new ArrayList<ByteBuf>();
        ClientResultCodec buffering = new ClientResultCodec() {
            @Override public Object encode(ClientHandlerContext context, Object result) {
                var buffer = Unpooled.buffer(4).writeInt(context.requestId());
                framed.add(buffer); return buffer;
            }
            @Override public Object encodeError(ClientHandlerContext context, int errorCode) {
                var buffer = Unpooled.buffer(4).writeInt(errorCode);
                framed.add(buffer); return buffer;
            }
        };
        try (var runtime = withoutCodec(handler).clientResultCodec(buffering).errorHandler(errors::add).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 7));
            assertEquals(1, framed.getFirst().refCnt(), "an accepted write hands the share to the transport");

            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.INACTIVE), 1, 8));
            // A refused write never consumed the message, so Runtime still owned it and released it.
            assertEquals(0, framed.getLast().refCnt());
            assertEquals(2, framed.size()); assertTrue(errors.isEmpty());
        } finally {
            framed.forEach(buffer -> { if (buffer.refCnt() > 0) buffer.release(); });
        }
    }

    @Test void aRefusedTransportWriteIsNotAFrameworkError() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        try (var runtime = builder(handler, new Codec(), errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.INACTIVE), 1, 7));
            assertEquals(1, writes.size()); assertTrue(errors.isEmpty());
        }
    }

    @Test void anEncodingFailureIsReportedAndFallsBackToOneErrorFraming() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>(); var writes = new ArrayList<>();
        var codec = new Codec();
        codec.encodeFailure = new IllegalStateException("cannot frame this result");
        try (var runtime = builder(handler, codec, errors).build()) {
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 7));
            assertEquals(List.of(new Framed(1, 7, FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR, null)), writes);
            assertEquals(1, errors.size());
            // An error framing that also fails is only reported; nothing further is attempted.
            codec.errorFailure = new IllegalStateException("cannot frame the error either");
            writes.clear(); errors.clear();
            runtime.dispatch(request(new Ping(42L), connection(writes, WriteStatus.ACCEPTED), 1, 8));
            assertTrue(writes.isEmpty());
            assertEquals(2, errors.size());
        }
    }

    @Test void aReturningHandlerRejectsANonClientContextBeforeAdmission() {
        var handler = new Handlers(); var errors = new ArrayList<RuntimeError>();
        var tasks = new ArrayDeque<Runnable>();
        try (var runtime = withoutCodec(handler)
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, t) -> {
                    tasks.add(t); return RouteExecuteStatus.ACCEPTED;
                }, 1, 2)))
                .clientResultCodec(new Codec()).errorHandler(errors::add).build()) {
            RpcHandlerContext rpc = new DefaultRpcHandlerContext(1, 42L, 0, 0, Metadata.empty(),
                    new Ping(42L), 10, 2, 1, 81);
            assertEquals(FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(rpc)).errorCode());
            assertTrue(tasks.isEmpty()); assertTrue(errors.isEmpty());
        }
    }

    @Handler(domain = 1, routeKey = "userId")
    static class UnregisteredResponse {
        @HandlerMethod public Joined ping(ClientHandlerContext context, Ping message) { return new Joined(1L); }
    }

    @Handler(domain = 1, routeKey = "userId")
    static class RpcContextAnswer {
        @HandlerMethod public Pong ping(RpcHandlerContext context, Ping message) { return new Pong("ok"); }
    }

    @Test void buildRejectsReturnedResponsesThatCannotBeDelivered() {
        var errors = new ArrayList<RuntimeError>();
        // Without a codec there is no wire format, so no Handler may return a response.
        assertEquals("Handlers that return a client response require clientResultCodec",
                assertThrows(IllegalArgumentException.class, () -> withoutCodec(new Handlers()).build()).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> builder(new UnregisteredResponse(), new Codec(), errors).build())
                .getMessage().contains("registered response"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> builder(new RpcContextAnswer(), new Codec(), errors).build())
                .getMessage().contains("needs a ClientHandlerContext"));
    }
}
