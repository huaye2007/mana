package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.timer.GameTimer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskInterceptorTest {
    private static final int PLAYER = 1, LOGIN = 2;
    private static final byte ROLE = 1, MARKER = 99;
    private static final int NOT_LOGGED_IN = 9001, LEVEL_TOO_LOW = 9002;   // the game's own codes

    record Buy(int item) {}
    record Hello() {}
    record Boom() {}
    record Later() {}
    record LevelUp(long playerId) {}

    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    private final GameContext role = GameContext.of(0, 0, ROLE, 1001), nobody = GameContext.of(0, 0);
    private GameRuntime runtime;
    private Dispatcher dispatcher;

    @Domain(PLAYER)
    class Shop {
        @HandleMethod void buy(HandleContext context, Buy buy) {
            trace.add("buy");
            context.reply("bought " + buy.item());
        }
        @HandleMethod void boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod void later(HandleContext context, Later later) {
            Thread.ofVirtual().start(() -> runtime.execute(context, again -> again.reply("later")));
        }
        @EventMethod(routeKey = "playerId") void levelUp(LevelUp event) { trace.add("event"); }
    }

    @Domain(LOGIN)
    class Accounts {
        @HandleMethod void hello(HandleContext context, Hello hello) {
            trace.add("hello");
            context.reply("hi");
        }
    }

    /** Records each task it sees, under its name; tasks that only mark a route's end are left out. */
    class Recorder implements TaskInterceptor {
        private final String name;

        Recorder(String name) { this.name = name; }

        @Override public void before(Context context) {
            if (context.businessType() == MARKER) return;
            trace.add(name + " before " + context.domain() + ":" + context.routeKey() + (Context.current() == context ? "" : " elsewhere"));
        }

        @Override public void after(Context context, Throwable failure) {
            if (context.businessType() == MARKER) return;
            trace.add(name + " after" + (failure == null ? "" : " " + failure.getMessage()));
        }
    }

    /** Lets only the login domain's handlers run for a source with no role. */
    static class LoginCheck implements TaskInterceptor {
        @Override public void before(Context context) {
            if (context instanceof HandleContext request && context.domain() != LOGIN && context.businessId() == 0) {
                request.fail(NOT_LOGGED_IN);
                throw new IllegalStateException("not logged in");   // keeps the task from running
            }
        }
    }

    private void start(TaskInterceptor... interceptors) {
        runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads())
                .domain(LOGIN, RouteExecutors.virtualThreads())
                .intercept(List.of(interceptors))
                .errorHandler((context, cause) -> errors.add(cause)).build();
        dispatcher = Dispatcher.builder(runtime).register(List.of(new Shop(), new Accounts())).build();
    }

    @AfterEach void stop() { runtime.close(); }

    /** Dispatches and waits for the answer, then for the task on the source's route to end, afters included. */
    private String ask(Context source, Object message) throws InterruptedException {
        dispatcher.dispatch(source, message, result -> {
            if (result.code() != SUCCESS) {
                answers.add("fail " + result.code() + (result.args().length == 0 ? "" : " " + String.join(",", result.args())));
                return;
            }
            trace.add("reply " + result.data());
            answers.add("ok " + result.data());
        });
        String answer = answers.poll(5, TimeUnit.SECONDS);
        awaitRoute(PLAYER, source.businessId());
        return answer;
    }

    /** Waits for the tasks given to a route so far, which run one at a time, to end. */
    private void awaitRoute(int domain, long routeKey) throws InterruptedException {
        CountDownLatch ended = new CountDownLatch(1);
        runtime.execute(new GameContext(domain, routeKey, MARKER, 0, Metadata.empty()), _ -> ended.countDown());
        assertTrue(ended.await(5, TimeUnit.SECONDS));
    }

    @Test void interceptorsWrapTheTaskOnItsRoute() throws Exception {
        start(new Recorder("a"), new Recorder("b"));
        assertEquals("ok bought 5", ask(role, new Buy(5)));
        assertEquals(List.of("a before " + PLAYER + ":1001", "b before " + PLAYER + ":1001", "buy", "reply bought 5",
                "b after", "a after"), trace, "befores in order, afters in reverse, an answer given while the task runs first");
    }

    @Test void everyEntryAndEveryContinuationIsATask() throws Exception {
        start(new Recorder("a"));
        EventBus.builder(runtime).register(List.of(new Shop())).build().publish(new LevelUp(7));
        awaitRoute(PLAYER, 7);
        assertEquals(List.of("a before " + PLAYER + ":7", "event", "a after"), trace);

        trace.clear();
        CountDownLatch fired = new CountDownLatch(1);
        try (GameTimer timer = new GameTimer(runtime)) {
            timer.after(GameContext.of(PLAYER, 7), Duration.ofMillis(1), _ -> fired.countDown());
            assertTrue(fired.await(5, TimeUnit.SECONDS));
            awaitRoute(PLAYER, 7);
        }
        assertEquals(List.of("a before " + PLAYER + ":7", "a after"), trace, "a timer's task");

        trace.clear();
        assertEquals("ok later", ask(role, new Later()));
        assertEquals(List.of("a before " + PLAYER + ":1001", "a after", "a before " + PLAYER + ":1001", "reply later", "a after"),
                trace, "the handler's task, then the task that continued the request on its route");
    }

    @Test void aBeforeRefusesARequestByFailingItAndThrowing() throws Exception {
        TaskInterceptor levelCheck = new TaskInterceptor() {
            @Override public void before(Context context) {
                if (context instanceof HandleContext request && context.businessId() == 1002) {
                    request.fail(LEVEL_TOO_LOW, "10");   // the level it needs
                    throw new IllegalStateException("level too low");
                }
            }
        };
        start(new Recorder("a"), new LoginCheck(), levelCheck, new Recorder("b"));
        assertEquals("fail " + NOT_LOGGED_IN, ask(nobody, new Buy(5)));
        assertEquals(List.of("a before " + PLAYER + ":0", "a after not logged in"), trace,
                "neither the task nor a later before ran, and the after of the before that returned did");
        assertEquals("fail " + LEVEL_TOO_LOW + " 10", ask(GameContext.of(0, 0, ROLE, 1002), new Buy(5)));

        trace.clear();
        assertEquals("ok hi", ask(nobody, new Hello()), "the login domain's handlers need no role");
        assertTrue(trace.contains("hello"));
        awaitRoute(PLAYER, 0);
        assertEquals("not logged in", errors.poll(5, TimeUnit.SECONDS).getMessage(), "what kept the task from running is an error like any other");
        assertEquals("level too low", errors.poll(5, TimeUnit.SECONDS).getMessage());
    }

    @Test void afterSeesWhatTheTaskThrew() throws Exception {
        start(new Recorder("a"));
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(role, new Boom()));
        assertEquals("a after boom", trace.getLast());
        assertTrue(errors.poll(5, TimeUnit.SECONDS) instanceof IllegalStateException);
    }

    @Test void aFailingInterceptorFailsTheRequestYetEveryAfterRuns() throws Exception {
        TaskInterceptor failingAfter = new TaskInterceptor() {
            @Override public void after(Context context, Throwable failure) {
                if (context.businessType() != MARKER) throw new IllegalStateException("after");
            }
        };
        start(new Recorder("a"), failingAfter, new Recorder("b"));
        dispatcher.dispatch(role, new Boom(), result -> answers.add(result.code() == SUCCESS ? "ok" : "fail " + result.code()));
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, answers.poll(5, TimeUnit.SECONDS));
        awaitRoute(PLAYER, 1001);
        assertEquals("a after boom", trace.getLast(), "every after ran, seeing what the task threw");
        Throwable error = errors.poll(5, TimeUnit.SECONDS);
        assertEquals("boom", error.getMessage());
        assertEquals("after", error.getSuppressed()[0].getMessage(), "the after's own failure goes along with it");

        trace.clear();
        assertEquals("ok bought 5", ask(role, new Buy(5)));
        assertEquals("a after", trace.getLast(), "an after sees what the task threw, not what another after did");
        assertEquals("after", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertNull(answers.poll(100, TimeUnit.MILLISECONDS));
    }
}
