package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GameRuntimeTest {
    private static final int PLAYER = 1, WORLD = 2;
    private static final byte PLAYER_ID = 1;

    static Stream<Arguments> executors() {
        return Stream.of(
                Arguments.of("virtualThreads", (Supplier<RouteExecutor>) RouteExecutors::virtualThreads),
                Arguments.of("striped", (Supplier<RouteExecutor>) () -> RouteExecutors.striped(4)));
    }

    /** Room for one task waiting. */
    static Stream<Arguments> limited() {
        return Stream.of(
                Arguments.of("virtualThreads", (Supplier<RouteExecutor>) () -> RouteExecutors.virtualThreads(1, Duration.ofSeconds(60))),
                Arguments.of("striped", (Supplier<RouteExecutor>) () -> RouteExecutors.striped(2, 1)));
    }

    private static GameRuntime runtime(Supplier<RouteExecutor> executor) {
        return GameRuntime.builder().domain(PLAYER, executor.get()).domain(WORLD, executor.get()).build();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void sameRouteRunsOneAtATimeInSubmissionOrder(String name, Supplier<RouteExecutor> executor) throws Exception {
        int submitters = 4, perSubmitter = 1000;
        AtomicInteger running = new AtomicInteger(), overlaps = new AtomicInteger();
        List<int[]> ran = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = runtime(executor)) {
            List<Thread> threads = new ArrayList<>();
            for (int s = 0; s < submitters; s++) {
                int submitter = s;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < perSubmitter; i++) {
                        int sequence = i;
                        runtime.execute(GameContext.of(PLAYER, 7), context -> {
                            if (running.incrementAndGet() != 1) overlaps.incrementAndGet();
                            ran.add(new int[] {submitter, sequence});
                            running.decrementAndGet();
                        });
                    }
                }));
            }
            for (Thread thread : threads) thread.join();
        }
        assertEquals(0, overlaps.get(), "tasks of one route never overlap");
        assertEquals(submitters * perSubmitter, ran.size());
        int[] last = new int[submitters];
        java.util.Arrays.fill(last, -1);
        for (int[] task : ran) {
            assertTrue(task[1] > last[task[0]], "each submitter's tasks run in its submission order");
            last[task[0]] = task[1];
        }
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void otherRouteKeysAndDomainsAreNotBlocked(String name, Supplier<RouteExecutor> executor) throws Exception {
        CountDownLatch release = new CountDownLatch(1), otherKey = new CountDownLatch(1), otherDomain = new CountDownLatch(1);
        try (GameRuntime runtime = runtime(executor)) {
            runtime.execute(GameContext.of(PLAYER, 1), context -> release.await());
            runtime.execute(GameContext.of(PLAYER, 2), context -> otherKey.countDown());
            runtime.execute(GameContext.of(WORLD, 1), context -> otherDomain.countDown());
            assertTrue(otherKey.await(5, TimeUnit.SECONDS), "a blocked key does not hold up another key");
            assertTrue(otherDomain.await(5, TimeUnit.SECONDS), "a blocked key does not hold up another domain");
            release.countDown();
        }
    }

    @Test void theContextIsCurrentOnlyWhileItsTaskRuns() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        GameContext context = GameContext.of(PLAYER, 9, PLAYER_ID, 9);
        try (GameRuntime runtime = runtime(RouteExecutors::virtualThreads)) {
            runtime.execute(context, task -> seen.add(Context.current()));
            assertSame(context, seen.poll(5, TimeUnit.SECONDS));
        }
        assertThrows(IllegalStateException.class, Context::current);
    }

    @Test void exceptionsGoToTheErrorHandlerAndLaterTasksStillRun() throws Exception {
        BlockingQueue<Object> errors = new LinkedBlockingQueue<>();
        CountDownLatch later = new CountDownLatch(1);
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads())
                .errorHandler((context, cause) -> errors.add(context.routeKey() + ":" + cause.getMessage())).build()) {
            runtime.execute(GameContext.of(PLAYER, 3), context -> { throw new IllegalStateException("boom"); });
            runtime.execute(GameContext.of(PLAYER, 3), context -> later.countDown());
            assertEquals("3:boom", errors.poll(5, TimeUnit.SECONDS));
            assertTrue(later.await(5, TimeUnit.SECONDS));
        }
    }

    @Test void aFullKeyRefusesTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads(2, Duration.ofSeconds(60))).build()) {
            runtime.execute(GameContext.of(PLAYER, 5), context -> { started.countDown(); release.await(); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runtime.execute(GameContext.of(PLAYER, 5), context -> {});
            runtime.execute(GameContext.of(PLAYER, 5), context -> {});
            GameRuntimeException full = assertThrows(GameRuntimeException.class,
                    () -> runtime.execute(GameContext.of(PLAYER, 5), context -> {}));
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, full.errorCode());
            runtime.execute(GameContext.of(PLAYER, 6), context -> {}); // the limit is per key
            release.countDown();
        }
    }

    @Test void aFullStripeRefusesTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        try (GameRuntime runtime = GameRuntime.builder().domain(WORLD, RouteExecutors.striped(1, 1)).build()) {
            runtime.execute(GameContext.of(WORLD, 1), context -> { started.countDown(); release.await(); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runtime.execute(GameContext.of(WORLD, 1), context -> {});
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(GameRuntimeException.class,
                    () -> runtime.execute(GameContext.of(WORLD, 2), context -> {})).errorCode());
            release.countDown();
        }
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void shutdownRefusesNewTasksAndFinishesAcceptedOnes(String name, Supplier<RouteExecutor> executor) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        GameRuntime runtime = runtime(executor);
        runtime.execute(GameContext.of(PLAYER, 1), context -> { release.await(); ran.incrementAndGet(); });
        for (int i = 0; i < 10; i++) runtime.execute(GameContext.of(PLAYER, 1), context -> ran.incrementAndGet());

        runtime.shutdown();
        assertEquals(RUNTIME_CLOSED, assertThrows(GameRuntimeException.class,
                () -> runtime.execute(GameContext.of(PLAYER, 2), context -> {})).errorCode());
        assertFalse(runtime.awaitTermination(Duration.ofMillis(100)), "accepted tasks are still running");

        release.countDown();
        assertTrue(runtime.awaitTermination(Duration.ofSeconds(5)));
        assertEquals(11, ran.get());
        assertTrue(runtime.isTerminated());
        runtime.close();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("limited")
    void aFullRouteRefusesAnEntryButNotATaskHandingWorkOn(String name, Supplier<RouteExecutor> executor) throws Exception {
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1), handedOn = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, executor.get()).build()) {
            runtime.execute(GameContext.of(PLAYER, 1), context -> { started.countDown(); release.await(); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runtime.execute(GameContext.of(PLAYER, 1), context -> ran.add("waiting"));
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(GameRuntimeException.class,
                    () -> runtime.execute(GameContext.of(PLAYER, 1), context -> ran.add("refused"))).errorCode());
            runtime.execute(GameContext.of(PLAYER, 2), context -> {     // as the second step of a trade
                runtime.execute(GameContext.of(PLAYER, 1), back -> ran.add("handed on"));
                handedOn.countDown();
            });
            assertTrue(handedOn.await(5, TimeUnit.SECONDS));
            release.countDown();
        }
        assertEquals(List.of("waiting", "handed on"), ran);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void workHandedOnDuringShutdownRunsAcrossDomains(String name, Supplier<RouteExecutor> executor) throws Exception {
        int hops = 200;
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        GameRuntime[] runtime = new GameRuntime[1];
        class Hop implements GameTask<GameContext> {
            private final int left;

            Hop(int left) { this.left = left; }

            @Override public void run(GameContext context) {
                ran.incrementAndGet();
                if (left > 0) runtime[0].execute(GameContext.of(left % 2 == 0 ? PLAYER : WORLD, left), new Hop(left - 1));
            }
        }
        runtime[0] = GameRuntime.builder().domain(PLAYER, executor.get()).domain(WORLD, executor.get())
                .errorHandler((context, cause) -> errors.add(cause)).build();
        runtime[0].execute(GameContext.of(PLAYER, 0), context -> {
            started.countDown();
            release.await();
            new Hop(hops).run(context);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        runtime[0].shutdown();
        assertEquals(RUNTIME_CLOSED, assertThrows(GameRuntimeException.class,
                () -> runtime[0].execute(GameContext.of(PLAYER, 1), context -> {})).errorCode());
        assertFalse(runtime[0].awaitTermination(Duration.ofMillis(50)), "a task still runs, and may hand work on");
        release.countDown();
        runtime[0].close();
        assertEquals(hops + 1, ran.get());
        assertTrue(errors.isEmpty(), () -> "refused on the way: " + errors);
        assertTrue(runtime[0].isTerminated());
    }

    /** A task holding something, which says what became of it. */
    private record Holding(List<String> trace, String name) implements GameTask<Context> {
        @Override public void run(Context context) { trace.add(name + " ran"); }
        @Override public void refused(Context context) { trace.add(name + " refused"); }
    }

    @Test void aTaskThatWillNotRunIsToldSo() {
        byte refusedRole = 2, throwingRole = 3;
        List<String> trace = new CopyOnWriteArrayList<>();
        TaskInterceptor gate = new TaskInterceptor() {
            @Override public boolean before(Context context) {
                if (context.businessType() == throwingRole) throw new IllegalStateException("boom");
                return context.businessType() != refusedRole;
            }
            @Override public void after(Context context, Throwable failure) { trace.add("after"); }
        };
        TaskInterceptor outer = new TaskInterceptor() {
            @Override public void after(Context context, Throwable failure) { trace.add("outer after"); }
        };
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.striped(1), RouteKeys.constant(0))
                .intercept(List.of(outer, gate)).errorHandler((context, cause) -> {}).build();
        runtime.execute(GameContext.of(PLAYER, 7), new Holding(trace, "accepted"));
        runtime.execute(GameContext.of(PLAYER, 7, refusedRole, 1), new Holding(trace, "stopped"));
        runtime.execute(GameContext.of(PLAYER, 7, throwingRole, 1), new Holding(trace, "broken"));
        runtime.close();
        GameRuntimeException refused = assertThrows(GameRuntimeException.class,
                () -> runtime.execute(GameContext.of(PLAYER, 7), new Holding(trace, "late")));
        assertEquals(RUNTIME_CLOSED, refused.errorCode());
        assertEquals(0, refused.getStackTrace().length, "refusals come in floods, and record no stack trace");
        assertEquals(List.of("accepted ran", "after", "outer after", "outer after", "stopped refused", "outer after", "broken refused",
                "late refused"), trace);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void whatARefusedTaskThrowsGoesToTheErrorHandlerAndItsRouteCarriesOn(String name, Supplier<RouteExecutor> executor) throws Exception {
        byte refusedRole = 2;
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        CountDownLatch next = new CountDownLatch(1);
        TaskInterceptor gate = new TaskInterceptor() {
            @Override public boolean before(Context context) { return context.businessType() != refusedRole; }
        };
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, executor.get()).intercept(List.of(gate))
                .errorHandler((context, cause) -> errors.add(cause)).build();
        runtime.execute(GameContext.of(PLAYER, 7, refusedRole, 1), new GameTask<Context>() {
            @Override public void run(Context context) {}
            @Override public void refused(Context context) { throw new IllegalStateException("released twice"); }
        });
        assertEquals("released twice", errors.poll(5, TimeUnit.SECONDS).getMessage());
        runtime.execute(GameContext.of(PLAYER, 7), context -> next.countDown());
        assertTrue(next.await(5, TimeUnit.SECONDS), "the route still runs tasks");
        runtime.shutdown();
        assertTrue(runtime.awaitTermination(Duration.ofSeconds(5)), "and the runtime can finish");
    }

    @Test void closingFromATaskIsRefused() throws Exception {
        BlockingQueue<Object> errors = new LinkedBlockingQueue<>();
        AtomicReference<GameRuntime> self = new AtomicReference<>();
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads())
                .errorHandler((context, cause) -> errors.add(cause)).build()) {
            self.set(runtime);
            runtime.execute(GameContext.of(PLAYER, 1), context -> self.get().close());
            assertTrue(errors.poll(5, TimeUnit.SECONDS) instanceof IllegalStateException);
        }
    }

    @Test void configurationIsValidated() {
        assertThrows(IllegalStateException.class, () -> GameRuntime.builder().build());
        assertThrows(IllegalArgumentException.class, () -> GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads()).domain(PLAYER, RouteExecutors.virtualThreads()));
        RouteExecutor shared = RouteExecutors.virtualThreads();
        assertThrows(IllegalArgumentException.class, () -> GameRuntime.builder().domain(PLAYER, shared).domain(WORLD, shared));
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).build()) {
            assertThrows(IllegalArgumentException.class, () -> runtime.execute(GameContext.of(WORLD, 1), context -> {}));
        }
    }
}
