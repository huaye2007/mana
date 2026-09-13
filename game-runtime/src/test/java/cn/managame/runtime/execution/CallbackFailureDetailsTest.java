package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CallbackFailureDetailsTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 1);

    @Test void checkedFailureKeepsIdentityOnRouteAndInUnhandledCompletion() throws Exception {
        var errors = new CopyOnWriteArrayList<HandlerException>();
        try (var runtime = builder().exceptionHandler(errors::add).build()) {
            var original = new IOException("disk", new IllegalStateException("cause"));
            AtomicReference<Throwable> seen = new AtomicReference<>();
            var callback = runtime.callback(new CallbackDefinition<String>(PLAYER, value -> fail(), error -> {
                assertEquals(PLAYER, HandlerContexts.current().route()); seen.set(error);
            }));
            assertTrue(callback.onFail(original));
            done(callback.completion());
            assertSame(original, seen.get());
            assertTrue(errors.isEmpty());
            var unhandled = runtime.callback(new CallbackDefinition<String>(PLAYER, value -> fail(), null));
            unhandled.onFail(original);
            var completion = assertThrows(ExecutionException.class, () -> done(unhandled.completion()));
            assertSame(original, completion.getCause().getCause());
            done(runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {}));
            assertEquals(1, errors.size());
            assertSame(original, errors.getFirst().getCause());
        }
    }

    @Test void rejectedCallbackCanBeAbortedWithoutRunningBusinessAndNotifiesOnRoute() throws Exception {
        try (var runtime = builder().executionDomain(PLAYER.type(), ExecutionDomain.platform("bounded")
                .threads(1).limits(new DomainLimits(1, 1, 0, 0)).build()).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var occupied = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            AtomicInteger business = new AtomicInteger();
            var callback = runtime.callback(new CallbackDefinition<String>(PLAYER, value -> business.incrementAndGet(),
                    error -> business.incrementAndGet()));
            HandlerException rejected;
            try {
                await(entered);
                rejected = assertThrows(HandlerException.class, () -> callback.onSuccess("value"));
                assertFalse(callback.isSignalled());
                assertFalse(callback.completion().isDone());
                assertTrue(callback.abort(rejected));
                assertSame(rejected, assertThrows(ExecutionException.class, () -> done(callback.completion())).getCause());
                assertFalse(callback.onSuccess("late"));
                assertFalse(callback.abort(new IOException("late")));
            } finally { release.countDown(); }
            done(occupied);
            AtomicReference<Throwable> notified = new AtomicReference<>();
            done(callback.completion().onComplete(PLAYER, error -> {
                assertEquals(PLAYER, HandlerContexts.current().route()); notified.set(error);
            }));
            assertSame(rejected, notified.get());
            assertEquals(0, business.get());
        }
    }

    @Test void abortAndSuccessRaceHasOneWinnerAndNeverCancelsAcceptedWork() throws Exception {
        try (var runtime = builder().build(); var threads = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                AtomicInteger business = new AtomicInteger();
                var callback = runtime.callback(new CallbackDefinition<String>(PLAYER, value -> business.incrementAndGet(), null));
                var error = new IOException("abandoned");
                CountDownLatch start = new CountDownLatch(1);
                var success = threads.submit(() -> { await(start); return callback.onSuccess("value"); });
                var abort = threads.submit(() -> { await(start); return callback.abort(error); });
                start.countDown();
                boolean accepted = success.get(3, TimeUnit.SECONDS);
                assertNotEquals(accepted, abort.get(3, TimeUnit.SECONDS));
                if (accepted) { done(callback.completion()); assertEquals(1, business.get()); }
                else {
                    assertSame(error, assertThrows(ExecutionException.class, () -> done(callback.completion())).getCause());
                    assertEquals(0, business.get());
                }
                assertFalse(callback.abort(error));
            }
        }
    }
}
