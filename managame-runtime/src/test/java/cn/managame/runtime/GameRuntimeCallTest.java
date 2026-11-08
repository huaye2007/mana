package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameRuntimeCallTest {
    private static final int PLAYER = 1, NOT_ENOUGH = 10_001;
    private static final GameContext ME = GameContext.of(PLAYER, 1, (byte) 1, 1), OTHER = GameContext.of(PLAYER, 2, (byte) 1, 2);

    record Trade(boolean has) {}
    record Broken() {}
    record BrokenAfter() {}

    private final BlockingQueue<HandleResult> answers = new LinkedBlockingQueue<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final List<String> ran = new CopyOnWriteArrayList<>();
    private GameRuntime runtime;

    /** As the game writes a trade: take on the other player's route, then finish on this one. */
    @Domain(PLAYER)
    class Trades {
        @HandleMethod void trade(HandleContext request, Trade trade) {
            runtime.call(OTHER, () -> {
                assertEquals(2, Context.current().routeKey(), "on the other route");
                return trade.has();
            }, taken -> {
                assertEquals(1, Context.current().routeKey(), "back on this route");
                assertEquals(request, Context.current(), "with the calling task's context");
                if (!taken) request.fail(NOT_ENOUGH);
                else request.reply("traded");
            });
        }

        @HandleMethod void broken(HandleContext request, Broken broken) {
            runtime.call(OTHER, () -> { throw new IllegalStateException("on the other route"); }, value -> ran.add("then"));
        }

        @HandleMethod void brokenAfter(HandleContext request, BrokenAfter broken) {
            runtime.call(OTHER, () -> 1, value -> { throw new IllegalStateException("back here"); });
        }
    }

    private Dispatcher start(RouteExecutor executor, List<TaskInterceptor> interceptors) {
        runtime = GameRuntime.builder().domain(PLAYER, executor).errorHandler((context, cause) -> errors.add(cause)).intercept(interceptors).build();
        return Dispatcher.builder(runtime).register(List.of(new Trades())).build();
    }

    private HandleResult answer() throws InterruptedException {
        HandleResult result = answers.poll(5, TimeUnit.SECONDS);
        assertNotNull(result, "the request was not answered");
        return result;
    }

    @AfterEach void stop() { runtime.close(); }

    @Test void theResultComesBackOnTheCallersRouteOnAVirtualThreadRoute() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads(), List.of());
        dispatcher.dispatch(ME, new Trade(true), answers::add);
        assertEquals("traded", answer().data());
        dispatcher.dispatch(ME, new Trade(false), answers::add);
        assertEquals(NOT_ENOUGH, answer().code());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void itIsTheSameOnPlatformThreads() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.striped(2), List.of());
        dispatcher.dispatch(ME, new Trade(true), answers::add);
        assertEquals("traded", answer().data());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void onOneThreadTheStepsDoNotWaitForEachOther() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.striped(1), List.of());
        dispatcher.dispatch(ME, new Trade(true), answers::add);
        assertEquals("traded", answer().data(), "a blocking wait here would never end");
    }

    @Test void workThatThrowsFailsTheRequestAndSkipsTheRest() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads(), List.of());
        dispatcher.dispatch(ME, new Broken(), answers::add);
        assertEquals(RUNTIME_EXECUTION_ERROR, answer().code());
        runtime.close();
        assertTrue(ran.isEmpty());
        assertEquals("on the other route", errors.getFirst().getMessage(), "and goes to the error handler");
    }

    @Test void aSecondStepThatThrowsFailsTheRequest() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads(), List.of());
        dispatcher.dispatch(ME, new BrokenAfter(), answers::add);
        assertEquals(RUNTIME_EXECUTION_ERROR, answer().code());
    }

    @Test void workAnInterceptorRefusesFailsTheRequest() throws Exception {
        TaskInterceptor notOnTheOtherRoute = new TaskInterceptor() {
            @Override public boolean before(Context context) { return context.routeKey() != 2; }
        };
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads(), List.of(notOnTheOtherRoute));
        dispatcher.dispatch(ME, new Trade(true), answers::add);
        assertEquals(RUNTIME_EXECUTION_ERROR, answer().code());
    }

    @Test void aFullRouteDoesNotRefuseEitherStep() throws Exception {
        Dispatcher dispatcher = start(RouteExecutors.virtualThreads(1, Duration.ofSeconds(60)), List.of());
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        runtime.execute(OTHER, context -> {
            running.countDown();
            release.await();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        runtime.execute(OTHER, context -> {});   // the other route is now full
        assertThrows(GameRuntimeException.class, () -> runtime.execute(OTHER, context -> {}));
        dispatcher.dispatch(ME, new Trade(true), answers::add);
        release.countDown();
        assertEquals("traded", answer().data());
    }

    @Test void aTaskThatIsNoRequestHasNothingToFail() throws Exception {
        start(RouteExecutors.virtualThreads(), List.of());
        CountDownLatch handled = new CountDownLatch(1);
        runtime.execute(ME, context -> runtime.call(OTHER, () -> {
            handled.countDown();
            throw new IllegalStateException("no one to answer");
        }, value -> ran.add("then")));
        assertTrue(handled.await(5, TimeUnit.SECONDS));
        runtime.close();
        assertTrue(ran.isEmpty());
        assertEquals("no one to answer", errors.getFirst().getMessage());
    }

    @Test void itIsForGameTasks() {
        start(RouteExecutors.virtualThreads(), List.of());
        assertThrows(IllegalStateException.class, () -> runtime.call(OTHER, () -> 1, value -> {}));
    }
}
