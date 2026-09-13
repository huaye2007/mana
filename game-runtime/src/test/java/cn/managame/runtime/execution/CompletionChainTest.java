package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CompletionChainTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 1);
    private static List<RouteTask> chain(RouteTask source, int length) {
        List<RouteTask> result = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            source = source.onComplete(PLAYER, error -> fail("Rejected notification must not run"));
            result.add(source);
        }
        return result;
    }
    private static void verifyFailed(List<RouteTask> tasks, Class<? extends Throwable> cause) {
        for (RouteTask task : tasks) {
            assertTrue(task.isDone(), "Every notification must reach a terminal state");
            var error = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertInstanceOf(cause, error.getCause().getCause());
        }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    @Test void twentyThousandClosedNotificationsAllFinishAndDeliveryThreadCanBeReused() throws Exception {
        AtomicInteger errors = new AtomicInteger();
        try (var runtime = GameRuntime.builder().exceptionHandler(error -> errors.incrementAndGet()).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(PLAYER, body -> {}, null));
            List<RouteTask> tasks = chain(callback.completion(), 20_000);
            runtime.close();
            assertThrows(HandlerException.class, () -> callback.onSuccess("late"));
            verifyFailed(tasks, RuntimeClosedException.class);
            assertEquals(20_001, errors.get());
        }
        // Attach to an already-completed task on this same thread after the long failure chain.
        try (var healthy = GameRuntime.builder().build()) {
            RouteTask done = healthy.dispatch(PLAYER.type(), PLAYER.key(), () -> {});
            done.get(3, TimeUnit.SECONDS);
            done.onComplete(PLAYER, error -> {
                assertNull(error); assertEquals(PLAYER, HandlerContexts.current().route());
            }).get(3, TimeUnit.SECONDS);
        }
    }

    @Test void timerCancellationUnderOverloadFinishesEntireFailureChain() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try (var runtime = GameRuntime.builder().automaticScheduling(false)
                .exceptionHandler(error -> errors.incrementAndGet())
                .executionDomain(PLAYER.type(), ExecutionDomain.platform("full").threads(1)
                        .maxTasks(1).maxTasksPerRoute(1).callbackReserve(0, 0).build()).build()) {
            RouteTask blocker = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                TimerTask timer = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofDays(1), () -> {});
                List<RouteTask> tasks = chain(timer.completion(), 20_000);
                assertTrue(timer.cancel());
                verifyFailed(tasks, RuntimeOverloadedException.class);
                assertEquals(20_000, errors.get());
                assertFalse(blocker.isDone());
            } finally { release.countDown(); }
            blocker.get(3, TimeUnit.SECONDS);
        }
    }
}
