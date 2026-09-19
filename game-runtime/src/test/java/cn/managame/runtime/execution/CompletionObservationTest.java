package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CompletionObservationTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 7);

    @Test void infrastructureObservesSuccessAfterBusinessReturns() throws Exception {
        var release = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        var errors = new AtomicReference<Throwable>();
        try (var runtime = GameRuntime.builder().build()) {
            try {
                var task = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> await(release));
                task.observeCompletion(error -> {
                    errors.set(error);
                    observed.countDown();
                });
                assertFalse(task.cancel(false));
                assertFalse(task.isDone());
                assertEquals(1, observed.getCount());
                release.countDown();
                assertTrue(observed.await(3, TimeUnit.SECONDS));
                assertNull(errors.get());
                task.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    @Test void infrastructureSeesFailureEvenWhenRuntimeCannotAcceptNotifications() throws Exception {
        var runtime = GameRuntime.builder().exceptionHandler(error -> {}).build();
        var callback = runtime.callback(new CallbackDefinition<String>(PLAYER, value -> fail(), null));
        var observed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        callback.completion().observeCompletion(error -> {
            failure.set(error);
            observed.countDown();
        });
        runtime.close();
        var rejected = assertThrows(HandlerException.class, () -> callback.onSuccess("late"));
        assertTrue(observed.await(3, TimeUnit.SECONDS));
        assertSame(rejected, failure.get());
        var waitFailure = assertThrows(ExecutionException.class, () -> callback.completion().get(3, TimeUnit.SECONDS));
        assertSame(rejected, waitFailure.getCause());
    }

    @Test void observerFailureCannotReplaceResultOrPreventOtherObservers() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.task(PLAYER);
            var observations = new AtomicInteger();
            task.observeCompletion(error -> { throw new AssertionError("observer"); });
            task.observeCompletion(error -> observations.incrementAndGet());
            assertDoesNotThrow(task::succeed);
            task.observeCompletion(error -> { throw new IllegalStateException("late observer"); });
            task.observeCompletion(error -> observations.incrementAndGet());
            assertEquals(2, observations.get());
            assertNull(task.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void lateObservationIsInlineAndDoesNotEnterTheTaskRoute() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {});
            task.get(3, TimeUnit.SECONDS);
            var caller = Thread.currentThread();
            var observerThread = new AtomicReference<Thread>();
            var context = new AtomicReference<Object>();
            task.observeCompletion(error -> {
                observerThread.set(Thread.currentThread());
                context.set(HandlerContexts.currentOrNull());
            });
            assertSame(caller, observerThread.get());
            assertNull(context.get());
        }
    }

    @Test void observationRunsOutsideTheCompletionLock() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.task(PLAYER);
            var registered = new CountDownLatch(1);
            var observed = new CountDownLatch(1);
            var registeredBeforeObserverReturned = new AtomicBoolean();
            task.observeCompletion(error -> {
                Thread.ofVirtual().start(() -> {
                    task.observeCompletion(ignored -> observed.countDown());
                    registered.countDown();
                });
                try {
                    registeredBeforeObserverReturned.set(registered.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            task.succeed();
            assertTrue(registeredBeforeObserverReturned.get(), "The observer must not hold the completion lock");
            assertEquals(0, registered.getCount());
            assertEquals(0, observed.getCount());
        }
    }

    @Test void concurrentRegistrationAndCompletionDeliverEveryObserverExactlyOnce() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.task(PLAYER);
            int count = 128;
            var counts = new AtomicIntegerArray(count);
            var start = new CountDownLatch(1);
            var registered = new CountDownLatch(count);
            var observed = new CountDownLatch(count);
            var unexpectedFailure = new AtomicReference<Throwable>();
            var workers = new ArrayList<Thread>();
            for (int i = 0; i < count; i++) {
                int slot = i;
                workers.add(Thread.ofVirtual().start(() -> {
                    await(start);
                    task.observeCompletion(error -> {
                        if (error != null) unexpectedFailure.set(error);
                        counts.incrementAndGet(slot);
                        observed.countDown();
                    });
                    registered.countDown();
                }));
            }
            workers.add(Thread.ofVirtual().start(() -> {
                await(start);
                task.succeed();
            }));
            start.countDown();
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            for (var worker : workers) assertTrue(worker.join(Duration.ofSeconds(3)));
            assertNull(unexpectedFailure.get());
            for (int i = 0; i < count; i++) assertEquals(1, counts.get(i));
            task.fail(new IllegalStateException("duplicate failure"));
            assertNull(task.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void timeoutInterruptionCancellationAndUncheckedFailureKeepTheirWaitContracts() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.task(PLAYER);
            assertThrows(TimeoutException.class, () -> task.get(0, TimeUnit.NANOSECONDS));
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, task::get); }
            finally { Thread.interrupted(); }
            var cause = new IllegalStateException("terminal");
            task.fail(cause);
            assertSame(cause, assertThrows(ExecutionException.class, task::get).getCause());
            assertSame(cause, assertThrows(CompletionException.class, task::join).getCause());
            task.succeed();
            assertSame(cause, assertThrows(ExecutionException.class, task::get).getCause());

            var timer = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofDays(1), () -> {});
            assertTrue(timer.cancel());
            assertTrue(timer.completion().isCancelled());
            assertThrows(CancellationException.class, timer.completion()::get);
            assertThrows(CancellationException.class, timer.completion()::join);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
