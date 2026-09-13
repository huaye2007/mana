package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RouteRuntimeTest {
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static void done(RouteTask task) throws Exception { task.get(5, TimeUnit.SECONDS); }
    static GameRuntime.Builder builder() { return GameRuntime.builder().automaticScheduling(false).exceptionHandler(e -> {}); }

    @Test void virtualThreadWaitRetainsRouteButOtherRoutesRun() throws Exception {
        try (GameRuntime runtime = builder().build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 7, () -> {
                assertTrue(Thread.currentThread().isVirtual()); order.add("A start"); entered.countDown(); await(release); order.add("A end");
            });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(TestRoutes.Player.class, 7, () -> order.add("B"));
                done(runtime.dispatch(TestRoutes.Guild.class, 7, () -> order.add("other")));
                done(runtime.dispatch(TestRoutes.Player.class, 8, () -> order.add("other key")));
                assertFalse(second.isDone());
                release.countDown(); done(first); done(second);
                assertEquals(List.of("A start", "other", "other key", "A end", "B"), order);
            } finally { release.countDown(); }
        }
        assertNull(HandlerContexts.currentOrNull());
    }

    @Test void sameRouteDispatchQueuesAndWaitingIsRejected() throws Exception {
        try (GameRuntime runtime = builder().build()) {
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicReference<RouteTask> nested = new AtomicReference<>();
            done(runtime.dispatch(TestRoutes.Account.class, Long.MIN_VALUE, () -> {
                order.add("outer");
                nested.set(runtime.dispatch(TestRoutes.Account.class, Long.MIN_VALUE, () -> order.add("nested")));
                assertFalse(nested.get().isDone());
                assertThrows(IllegalStateException.class, () -> nested.get().join());
                assertThrows(IllegalStateException.class, () -> nested.get().get());
                assertThrows(IllegalStateException.class, () -> nested.get().get(1, TimeUnit.SECONDS));
                order.add("outer end");
            }));
            done(nested.get());
            assertEquals(List.of("outer", "outer end", "nested"), order);
        }
    }

    @Test void exceptionReleasesRouteAndReportsContext() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = builder().exceptionHandler(errors::add).build()) {
            RouteTask failure = runtime.dispatch(TestRoutes.Battle.class, 0, () -> { throw new AssertionError("business"); });
            assertThrows(ExecutionException.class, () -> done(failure));
            done(runtime.dispatch(TestRoutes.Battle.class, 0, () -> assertEquals(0, HandlerContexts.current().routeKey())));
            assertEquals(1, errors.size()); assertInstanceOf(AssertionError.class, errors.getFirst().getCause());
            assertEquals(new Route(TestRoutes.Battle.class, 0), errors.getFirst().context().route());
        }
    }

    @Test void brokenExceptionObserverCannotBlockNextTask() throws Exception {
        try (GameRuntime runtime = builder().exceptionHandler(e -> { throw new IllegalStateException("observer"); }).build()) {
            RouteTask failed = runtime.dispatch(TestRoutes.Guild.class, 2, () -> { throw new IllegalArgumentException(); });
            assertThrows(ExecutionException.class, () -> done(failed));
            done(runtime.dispatch(TestRoutes.Guild.class, 2, () -> {}));
        }
    }

    @Test void concurrentSubmittersNeverOverlapSameRoute() throws Exception {
        try (GameRuntime runtime = builder().build(); ExecutorService callers = Executors.newFixedThreadPool(8)) {
            AtomicInteger active = new AtomicInteger(), completed = new AtomicInteger();
            List<Future<RouteTask>> submitted = new ArrayList<>();
            for (int i = 0; i < 1000; i++) submitted.add(callers.submit(() -> runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                assertEquals(1, active.incrementAndGet());
                Thread.yield();
                completed.incrementAndGet(); assertEquals(0, active.decrementAndGet());
            })));
            for (Future<RouteTask> task : submitted) done(task.get());
            assertEquals(1000, completed.get());
        }
    }

    @Test void sequentialSubmissionIsFifoAcrossIdleLaneRecreation() throws Exception {
        try (GameRuntime runtime = builder().build()) {
            List<Integer> seen = new ArrayList<>(); List<RouteTask> tasks = new ArrayList<>();
            for (int i = 0; i < 1000; i++) { int index = i; tasks.add(runtime.dispatch(TestRoutes.Player.class, 1, () -> seen.add(index))); }
            for (RouteTask task : tasks) done(task);
            assertEquals(java.util.stream.IntStream.range(0, 1000).boxed().toList(), seen);
        }
    }

    @Test void closeDrainsAcceptedTasksRejectsNewWorkAndCannotRunInsideHandler() throws Exception {
        GameRuntime runtime = builder().build();
        AtomicInteger count = new AtomicInteger();
        done(runtime.dispatch(TestRoutes.Account.class, 1, () -> assertThrows(IllegalStateException.class, runtime::close)));
        for (int i = 0; i < 100; i++) runtime.dispatch(TestRoutes.Account.class, 1, count::incrementAndGet);
        runtime.close(); runtime.close();
        assertEquals(100, count.get()); assertEquals(0, runtime.activeRoutes());
        assertThrows(RejectedExecutionException.class, () -> runtime.dispatch(TestRoutes.Account.class, 1, () -> {}));
    }

    @Test void differentRuntimeWithSameRouteIsNotMistakenForSelfWait() throws Exception {
        try (GameRuntime a = builder().build(); GameRuntime b = builder().build()) {
            done(a.dispatch(TestRoutes.Player.class, 1, () -> b.dispatch(TestRoutes.Player.class, 1, () -> {}).join()));
        }
    }
}
