package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.route.Route;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class AsyncCompletionTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 7);
    private static final Route GUILD = new Route(TestRoutes.Guild.class, 9);
    private static final MetadataKey<String> TRACE = MetadataKey.application(900, String.class);

    private static GameRuntime.Builder oneWorker(int perRoute, int reserve) {
        var domain = ExecutionDomain.platform("business").threads(1)
                .maxTasks(100).maxTasksPerRoute(perRoute).callbackReserve(reserve, reserve).build();
        return builder().executionDomain(PLAYER.type(), domain).executionDomain(GUILD.type(), domain);
    }

    @Test void completionReturnsToCallerRouteWithCapturedMetadataWithoutBlockingSharedWorker() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<RouteTask> notification = new AtomicReference<>();
        try (GameRuntime runtime = oneWorker(16, 2).build()) {
            done(runtime.dispatch(new HandlerContext(PLAYER, Metadata.empty().with(TRACE, "caller")), "caller", () -> {
                RouteTask guild = runtime.dispatch(GUILD.type(), GUILD.key(), () -> {
                    assertEquals(GUILD, HandlerContexts.current().route());
                    order.add("guild");
                });
                notification.set(guild.onComplete(error -> {
                    assertNull(error);
                    assertEquals(PLAYER, HandlerContexts.current().route());
                    assertEquals("caller", HandlerContexts.current().metadata().get(TRACE));
                    order.add("notification");
                }));
                assertFalse(notification.get().isDone());
                order.add("caller returned");
            }));
            done(notification.get());
            assertEquals(List.of("caller returned", "guild", "notification"), order);
        }
    }

    @Test void alreadyCompletedTaskStillQueuesItsNotificationBehindTheCaller() throws Exception {
        AtomicReference<RouteTask> notification = new AtomicReference<>();
        List<String> order = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = oneWorker(16, 2).build()) {
            RouteTask completed = runtime.dispatch(GUILD.type(), GUILD.key(), () -> {});
            done(completed);
            done(runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {
                notification.set(completed.onComplete(error -> order.add("notification")));
                assertFalse(notification.get().isDone());
                order.add("caller returned");
            }));
            done(notification.get());
            assertEquals(List.of("caller returned", "notification"), order);
        }
    }

    @Test void explicitDestinationAcceptsExternalRegistrationAndPropagatesTaskFailure() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        IllegalArgumentException cause = new IllegalArgumentException("guild failed");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (GameRuntime runtime = oneWorker(16, 2).exceptionHandler(errors::add).build()) {
            RouteTask source = runtime.dispatch(GUILD.type(), GUILD.key(), () -> { throw cause; });
            ExecutionException sourceFailure = assertThrows(ExecutionException.class, () -> done(source));
            assertThrows(IllegalStateException.class, () -> source.onComplete(error -> {}));
            RouteTask notification = source.onComplete(PLAYER, error -> {
                assertEquals(PLAYER, HandlerContexts.current().route());
                observed.set(error);
            });
            done(notification);
            assertSame(sourceFailure.getCause(), observed.get());
            assertSame(cause, observed.get().getCause());
            assertEquals(1, errors.size());
        }
    }

    @Test void completionListenerFailureIsReportedAndFailsItsOwnHandle() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        IllegalStateException cause = new IllegalStateException("listener failed");
        try (GameRuntime runtime = oneWorker(16, 2).exceptionHandler(errors::add).build()) {
            RouteTask source = runtime.dispatch(GUILD.type(), GUILD.key(), () -> {});
            RouteTask notification = source.onComplete(PLAYER, error -> { throw cause; });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> done(notification));
            assertSame(cause, failure.getCause().getCause());
            done(source);
            assertEquals(1, errors.size());
            assertEquals(PLAYER, errors.getFirst().context().route());
        }
    }

    @Test void rejectedNotificationFailsItsHandleAndReportsInsteadOfHanging() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = oneWorker(1, 0).exceptionHandler(errors::add).build()) {
            RouteTask source = runtime.dispatch(GUILD.type(), GUILD.key(), () -> {});
            done(source);
            RouteTask blocked = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                RouteTask notification = source.onComplete(PLAYER, error -> fail("Rejected listener must not run"));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> done(notification));
                assertInstanceOf(RuntimeOverloadedException.class, failure.getCause().getCause());
                assertEquals(1, errors.size());
                assertEquals("task completion dispatch", errors.getFirst().source());
            } finally { release.countDown(); }
            done(blocked);
        }
    }

    @Test void callbackSignalAndBusinessCompletionHaveSeparateObservableMeanings() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = oneWorker(16, 2).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> {
                assertEquals("decoded", body);
                entered.countDown(); await(release);
            }, null));
            RouteTask completion = callback.completion();
            assertFalse(callback.isSignalled());
            assertFalse(completion.isDone());
            try {
                assertTrue(callback.onSuccess("decoded"));
                await(entered);
                assertTrue(callback.isSignalled());
                assertFalse(completion.isDone());
                assertFalse(callback.onFail(123));
                assertSame(completion, callback.completion());
            } finally { release.countDown(); }
            done(completion);
            assertTrue(callback.isSignalled());
        }
    }

    @Test void rejectedCallbackCanRetryUsingTheSamePendingCompletionHandle() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = oneWorker(1, 0).exceptionHandler(errors::add).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> calls.incrementAndGet(), null));
            RouteTask completion = callback.completion();
            RouteTask blocked = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                assertThrows(HandlerException.class, () -> callback.onSuccess("first attempt"));
                assertFalse(callback.isSignalled());
                assertFalse(completion.isDone());
                assertSame(completion, callback.completion());
                assertEquals(1, errors.size());
            } finally { release.countDown(); }
            done(blocked);
            assertTrue(callback.onSuccess("retry"));
            done(completion);
            assertTrue(callback.isSignalled());
            assertEquals(1, calls.get());
        }
    }

    @Test void callbackBusinessFailureIsObservableThroughItsCompletionHandle() {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        IllegalArgumentException cause = new IllegalArgumentException("apply response failed");
        try (GameRuntime runtime = oneWorker(16, 2).exceptionHandler(errors::add).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> { throw cause; }, null));
            assertTrue(callback.onSuccess("decoded"));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> done(callback.completion()));
            assertSame(cause, failure.getCause().getCause());
            assertTrue(callback.isSignalled());
            assertFalse(callback.onSuccess("duplicate"));
            assertEquals(1, errors.size());
        }
    }

    @Test void acceptedCallbackBackendFailureCompletesTheSameHandleExceptionally() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var domain = ExecutionDomain.custom("broken", () -> new RouteDispatcher() {
            final ExecutorService worker = Executors.newSingleThreadExecutor();
            public void dispatch(Route route, Runnable batch) { worker.execute(batch); }
            public void reschedule(Route route, Runnable batch) { throw new IllegalStateException("backend failed"); }
            public void shutdown() { worker.shutdown(); }
            public boolean awaitTermination(Duration timeout) throws InterruptedException {
                return worker.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        }).tasksPerTurn(1).build();
        try (GameRuntime runtime = builder().executionDomain(PLAYER.type(), domain).exceptionHandler(errors::add).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> fail("Backend failed before callback"), null));
            RouteTask completion = callback.completion();
            RouteTask blocked = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                assertTrue(callback.onSuccess("accepted"));
                assertTrue(callback.isSignalled());
                assertFalse(completion.isDone());
            } finally { release.countDown(); }
            done(blocked);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> done(completion));
            assertEquals("backend failed", failure.getCause().getCause().getCause().getMessage());
            assertSame(completion, callback.completion());
            assertTrue(callback.isSignalled());
            assertEquals(1, errors.size());
        }
    }

    @Test void closingDrainsAcceptedCallbacksButRejectsNotificationsNotYetSubmitted() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = oneWorker(16, 2).exceptionHandler(errors::add).build()) {
            RouteTask source = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> callbacks.incrementAndGet(), null));
            RouteTask notification = source.onComplete(GUILD, error -> fail("Notification was not admitted before shutdown"));
            try {
                await(entered);
                assertTrue(callback.onSuccess("accepted"));
                assertFalse(runtime.close(Duration.ZERO).terminated());
            } finally { release.countDown(); }
            done(source);
            done(callback.completion());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> done(notification));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause().getCause());
            assertEquals(1, callbacks.get());
            assertEquals(1, errors.size());
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
        }
    }
}
