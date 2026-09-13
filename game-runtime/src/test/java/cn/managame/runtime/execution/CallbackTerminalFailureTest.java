package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CallbackTerminalFailureTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 1);

    @Test void lateResponseAfterCloseFailsStableHandleAndCannotRetry() throws Exception {
        var errors = new CopyOnWriteArrayList<HandlerException>();
        var runtime = GameRuntime.builder().exceptionHandler(errors::add).build();
        try {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> fail("late response"), null));
            RouteTask completion = callback.completion();
            RouteTask notification = completion.onComplete(PLAYER, failure -> fail("closed runtime"));
            assertTrue(runtime.close(Duration.ofSeconds(3)).terminated());
            HandlerException rejected = assertThrows(HandlerException.class, () -> callback.onSuccess("late"));
            assertInstanceOf(RuntimeClosedException.class, rejected.getCause());
            assertSame(completion, callback.completion());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> completion.get(3, TimeUnit.SECONDS));
            assertSame(rejected, failure.getCause());
            assertTrue(callback.isSignalled());
            assertFalse(callback.onSuccess("retry"));
            assertFalse(callback.onFail(1));
            assertTrue(notification.isDone());
            assertTrue(errors.contains(rejected));
        } finally { runtime.close(); }
    }

    @Test void terminalFailureIsVisibleBeforeErrorObserverRuns() {
        AtomicReference<RuntimeCallback<String>> callback = new AtomicReference<>();
        AtomicBoolean observed = new AtomicBoolean();
        var runtime = GameRuntime.builder().exceptionHandler(error -> {
            assertTrue(callback.get().completion().isDone());
            observed.set(true);
        }).build();
        callback.set(runtime.callback(new CallbackDefinition<>(PLAYER, body -> {}, null)));
        runtime.close();
        assertThrows(HandlerException.class, () -> callback.get().onFail(7));
        assertTrue(observed.get());
    }

    @Test void unexpectedBackendFailureIsTerminalInsteadOfAnEndlessRetry() throws Exception {
        RouteDispatcher backend = new RouteDispatcher() {
            public void dispatch(Route route, Runnable action) { throw new IllegalStateException("backend broken"); }
            public void reschedule(Route route, Runnable action) { throw new AssertionError(); }
            public void shutdown() {}
            public boolean awaitTermination(Duration timeout) { return true; }
        };
        try (var runtime = GameRuntime.builder().exceptionHandler(error -> {})
                .executionDomain(PLAYER.type(), ExecutionDomain.custom("broken", () -> backend).build()).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> fail("never admitted"), null));
            HandlerException rejected = assertThrows(HandlerException.class, () -> callback.onSuccess("body"));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> callback.completion().get(3, TimeUnit.SECONDS));
            assertSame(rejected, failure.getCause());
            assertFalse(callback.onSuccess("retry"));
        }
    }
}
