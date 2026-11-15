package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.dispatch.HandleResult;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContinuationTest {
    private static final int PLAYER = 1, WORLD_FULL = 10_001;
    private static final GameContext ME = GameContext.of(PLAYER, 1, (byte) 1, 1);

    record EnterWorld(boolean room) {}
    record EnterBroken() {}

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

    /** Stands in for another server: it answers on a thread of its own, as an RPC callback runs on an EventLoop. */
    private final ExecutorService world = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("world").factory());
    private final BlockingQueue<HandleResult> answers = new LinkedBlockingQueue<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private GameRuntime runtime;

    /** As the game writes a call to another server: ask it, and go on with its answer on the player's route. */
    @Domain(PLAYER)
    class Enter {
        @HandleMethod void enter(HandleContext request, EnterWorld enter) {
            Continuation back = runtime.continuation();
            world.execute(() -> {
                boolean joined = enter.room();                          // the answer, decoded on the other thread
                back.execute(context -> {
                    assertSame(request, context, "with the task's context");
                    assertSame(request, Context.current());
                    if (joined) request.reply("entered");
                    else request.fail(WORLD_FULL);
                });
            });
        }

        @HandleMethod void broken(HandleContext request, EnterBroken enter) {
            Continuation back = runtime.continuation();
            world.execute(() -> back.execute(context -> { throw new IllegalStateException("back here"); }));
        }
    }

    private Dispatcher start(RouteExecutor executor) {
        runtime = GameRuntime.builder().domain(PLAYER, executor).errorHandler((context, cause) -> errors.add(cause)).build();
        return Dispatcher.builder(runtime).register(List.of(new Enter())).build();
    }

    private HandleResult answer() throws InterruptedException {
        HandleResult result = answers.poll(5, TimeUnit.SECONDS);
        assertNotNull(result, "the request was not answered");
        return result;
    }

    @AfterEach void stop() {
        if (runtime != null) runtime.close();
        world.shutdownNow();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("executors")
    void whatComesBackGoesOnOnTheTasksRoute(String name, Supplier<RouteExecutor> executor) throws Exception {
        Dispatcher dispatcher = start(executor.get());
        dispatcher.dispatch(ME, new EnterWorld(true), answers::add);
        assertEquals("entered", answer().data());
        dispatcher.dispatch(ME, new EnterWorld(false), answers::add);
        assertEquals(WORLD_FULL, answer().code());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void whatGoesOnAndThrowsFailsTheRequest() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads());
        dispatcher.dispatch(ME, new EnterBroken(), answers::add);
        assertEquals(RUNTIME_EXECUTION_ERROR, answer().code());
        runtime.close();
        assertEquals("back here", errors.getFirst().getMessage(), "and goes to the error handler");
    }

    @ParameterizedTest(name = "{0}") @MethodSource("limited")
    void aFullRouteRefusesAnEntryButNotWhatComesBack(String name, Supplier<RouteExecutor> executor) throws Exception {
        start(executor.get());
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<Continuation> taken = new LinkedBlockingQueue<>();
        List<String> ran = new CopyOnWriteArrayList<>();
        runtime.execute(ME, context -> {
            taken.add(runtime.continuation());
            release.await();
        });
        Continuation back = taken.poll(5, TimeUnit.SECONDS);
        assertNotNull(back, "the task did not start");
        runtime.execute(ME, context -> ran.add("waiting"));   // the route is now full
        assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(GameRuntimeException.class,
                () -> runtime.execute(ME, context -> ran.add("refused"))).errorCode());
        back.execute(context -> ran.add("came back"));         // from this thread, which is no game task
        back.execute(context -> ran.add("came back again"));   // as often as results come
        release.countDown();
        runtime.close();
        assertEquals(List.of("waiting", "came back", "came back again"), ran, "in the order they were handed over");
    }

    @Test void aRuntimeShutDownRefusesItAndTellsTheTask() throws Exception {
        start(RouteExecutors.virtualThreads());
        BlockingQueue<Continuation> taken = new LinkedBlockingQueue<>();
        runtime.execute(ME, context -> taken.add(runtime.continuation()));
        Continuation back = taken.poll(5, TimeUnit.SECONDS);
        assertNotNull(back, "the task did not run");
        runtime.close();

        List<String> told = new CopyOnWriteArrayList<>();
        GameTask<Context> late = new GameTask<>() {
            @Override public void run(Context context) { told.add("ran"); }
            @Override public void refused(Context context) { told.add("refused"); }
        };
        assertEquals(RUNTIME_CLOSED, assertThrows(GameRuntimeException.class, () -> back.execute(late)).errorCode());
        assertEquals(List.of("refused"), told);
    }

    @Test void aRequestWhoseAnswerComesTooLateIsFailedWithTheRefusal() throws Exception {
        BlockingQueue<Continuation> taken = new LinkedBlockingQueue<>();
        runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).build();
        @Domain(PLAYER)
        class Slow {
            @HandleMethod void enter(HandleContext request, EnterWorld enter) { taken.add(runtime.continuation()); }
        }
        Dispatcher.builder(runtime).register(List.of(new Slow())).build().dispatch(ME, new EnterWorld(true), answers::add);
        Continuation back = taken.poll(5, TimeUnit.SECONDS);
        assertNotNull(back, "the handler did not run");
        runtime.close();
        assertThrows(GameRuntimeException.class, () -> back.execute(context -> ((HandleContext) context).reply("entered")));
        assertEquals(RUNTIME_CLOSED, answer().code(), "a request is answered whether its work goes on or not");
    }

    @Test void itIsTakenInAGameTask() {
        start(RouteExecutors.virtualThreads());
        assertThrows(IllegalStateException.class, () -> runtime.continuation());
    }
}
