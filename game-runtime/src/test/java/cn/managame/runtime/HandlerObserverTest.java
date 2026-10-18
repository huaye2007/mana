package cn.managame.runtime;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.core.Metadata;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.handler.HandlerObserver;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** HandlerObserver watches Handler methods and can never change what they do. */
class HandlerObserverTest {
    record Ping(long userId) {}
    record Nested(long userId) {}
    record Notice(int routeDomain, long routeKey) implements Event {}

    record Seen(String phase, Object message, long nanos, Throwable cause, HandlerContext context) {}

    static final class Recorder implements HandlerObserver {
        final List<Seen> seen = new ArrayList<>();
        volatile RuntimeException failure;

        @Override public void onStart(HandlerContext context) {
            seen.add(new Seen("start", context.message(), 0, null, context));
            if (failure != null) throw failure;
        }
        @Override public void onFinish(HandlerContext context, long elapsedNanos) {
            seen.add(new Seen("finish", context.message(), elapsedNanos, null, context));
            if (failure != null) throw failure;
        }
        @Override public void onError(HandlerContext context, long elapsedNanos, Throwable cause) {
            seen.add(new Seen("error", context.message(), elapsedNanos, cause, context));
        }
    }

    @Handler(domain = 1, routeKey = "userId")
    static class Handlers {
        volatile GameRuntime runtime;
        volatile RuntimeException failure;
        final List<Object> ran = new ArrayList<>();

        @HandlerMethod public void ping(HandlerContext context, Ping message) {
            ran.add(message);
            if (message.userId() == 1 && runtime != null) runtime.dispatch(context(new Nested(1)));
            if (failure != null) throw failure;
        }
        @HandlerMethod public void nested(HandlerContext context, Nested message) { ran.add(message); }
    }

    static class Listener {
        final List<Notice> seen = new ArrayList<>();
        @EventMethod public void onNotice(Notice event) { seen.add(event); }
    }

    private static DefaultHandlerContext context(Object message) {
        return new DefaultHandlerContext(1, 1L, 0, 0, Metadata.empty(), message);
    }

    private final List<RuntimeError> errors = new ArrayList<>();

    private GameRuntimeBuilder builder(Object handler, Recorder recorder, RouteExecuteStatus status) {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "one")))
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, task) -> {
                    if (status == RouteExecuteStatus.ACCEPTED) task.run();
                    return status;
                }, 1)))
                .protocols(ProtocolRegistry.of(
                        ProtocolDescriptor.request(1, Ping.class),
                        ProtocolDescriptor.request(2, Nested.class)))
                .handlers(List.of(handler)).handlerObserver(recorder).errorHandler(errors::add);
    }

    @Test void aSuccessfulMethodIsStartedThenFinishedWithItsOwnContext() {
        var handler = new Handlers(); var recorder = new Recorder();
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.ACCEPTED).build()) {
            var context = context(new Ping(42));
            runtime.dispatch(context);
            assertEquals(List.of("start", "finish"), recorder.seen.stream().map(Seen::phase).toList());
            recorder.seen.forEach(seen -> assertSame(context, seen.context()));
            assertTrue(recorder.seen.getLast().nanos() > 0, "the method's own duration is measured");
            assertNull(recorder.seen.getLast().cause());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void aThrowingMethodIsStartedThenFailedAndStillReachesTheErrorHandler() {
        var handler = new Handlers(); var recorder = new Recorder();
        handler.failure = new IllegalStateException("business failure");
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.ACCEPTED).build()) {
            runtime.dispatch(context(new Ping(42)));
            assertEquals(List.of("start", "error"), recorder.seen.stream().map(Seen::phase).toList());
            assertSame(handler.failure, recorder.seen.getLast().cause());
            // Observation does not replace error reporting; both see the same failure.
            assertEquals(1, errors.size());
            assertSame(handler.failure, errors.getFirst().cause());
        }
    }

    @Test void anObserverThatThrowsIsReportedAndChangesNothing() {
        var handler = new Handlers(); var recorder = new Recorder();
        recorder.failure = new IllegalStateException("observer is broken");
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.ACCEPTED).build()) {
            var message = new Ping(42);
            runtime.dispatch(context(message));
            // The Handler ran normally and was still observed to completion.
            assertEquals(List.of(message), handler.ran);
            assertEquals(List.of("start", "finish"), recorder.seen.stream().map(Seen::phase).toList());
            // Both observer failures were reported, and neither became the Handler's failure.
            assertEquals(2, errors.size());
            errors.forEach(error -> assertSame(recorder.failure, error.cause()));
        }
    }

    @Test void workRejectedBeforeAdmissionIsNeverObserved() {
        var handler = new Handlers(); var recorder = new Recorder();
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.OVERLOADED).build()) {
            assertEquals(FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(context(new Ping(42)))).errorCode());
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(new DefaultHandlerContext(1, 0L, 0, 0, Metadata.empty(), new Ping(42)))).errorCode());
            assertTrue(recorder.seen.isEmpty());
            assertTrue(handler.ran.isEmpty());
        }
    }

    @Test void nestedSameRouteDispatchNestsItsObservations() {
        var handler = new Handlers(); var recorder = new Recorder();
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.ACCEPTED).build()) {
            handler.runtime = runtime;
            runtime.dispatch(context(new Ping(1)));
            // Inline execution means the inner method opens and closes inside the outer one.
            assertEquals(List.of("start", "start", "finish", "finish"),
                    recorder.seen.stream().map(Seen::phase).toList());
            assertEquals(List.of(new Ping(1), new Nested(1), new Nested(1), new Ping(1)),
                    recorder.seen.stream().map(Seen::message).toList());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void onlyHandlerMethodsAreObserved() throws Exception {
        var handler = new Handlers(); var recorder = new Recorder(); var listener = new Listener();
        var fired = new CountDownLatch(1);
        try (var runtime = builder(handler, recorder, RouteExecuteStatus.ACCEPTED)
                .eventHandlers(List.of(listener)).build()) {
            runtime.eventBus().publish(new Notice(1, 1L));
            runtime.timer().schedule(1, 1L, Duration.ZERO, fired::countDown);
            assertTrue(fired.await(5, TimeUnit.SECONDS));

            assertEquals(1, listener.seen.size());
            // Event listeners and timer actions are Route work, but they are not Handler methods.
            assertTrue(recorder.seen.isEmpty());
            assertTrue(errors.isEmpty());
        }
    }
}
