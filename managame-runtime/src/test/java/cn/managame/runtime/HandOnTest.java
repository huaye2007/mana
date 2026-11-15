package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** What has already happened outside and has no second try, such as a player's connection closing, gets in however full its route. */
class HandOnTest {
    private static final int PLAYER = 1;
    private static final GameContext PLAYER_7 = GameContext.of(PLAYER, 7, (byte) 1, 7);

    /** Room for one task waiting. */
    static Stream<Arguments> limited() {
        return Stream.of(
                Arguments.of("virtualThreads", (Supplier<RouteExecutor>) () -> RouteExecutors.virtualThreads(1, Duration.ofSeconds(60))),
                Arguments.of("striped", (Supplier<RouteExecutor>) () -> RouteExecutors.striped(2, 1)));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("limited")
    void aFullRouteRefusesARequestButNotThePlayerGoingOffline(String name, Supplier<RouteExecutor> executor) throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = GameRuntime.builder().domain(PLAYER, executor.get()).build()) {
            runtime.execute(PLAYER_7, context -> {
                started.countDown();
                release.await();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "the task did not start");
            runtime.execute(PLAYER_7, context -> ran.add("a request waiting"));   // the route is now full
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(GameRuntimeException.class,
                    () -> runtime.execute(PLAYER_7, context -> ran.add("a request refused"))).errorCode(), "its client may send it again");

            // The connection closed, on an EventLoop, which is no game task: the player leaves all the same.
            runtime.handOn(PLAYER_7, context -> ran.add("left as " + (Context.current() == PLAYER_7 ? "player 7" : "someone else")));
            release.countDown();
        }
        assertEquals(List.of("a request waiting", "left as player 7"), ran, "on its route, after what waited there");
    }

    @Test void aRuntimeShutDownRefusesItAndTellsTheTask() {
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).build();
        runtime.close();
        List<String> told = new CopyOnWriteArrayList<>();
        GameTask<Context> late = new GameTask<>() {
            @Override public void run(Context context) { told.add("ran"); }
            @Override public void refused(Context context) { told.add("refused"); }
        };
        assertEquals(RUNTIME_CLOSED, assertThrows(GameRuntimeException.class, () -> runtime.handOn(PLAYER_7, late)).errorCode());
        assertEquals(List.of("refused"), told);
    }
}
