package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.network.http.HttpExchange;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.dispatch.HandleResult;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpResult;
import cn.managame.runtime.http.HttpRoutes;
import cn.managame.runtime.timer.Cron;
import cn.managame.runtime.timer.GameTimer;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RuntimeMetricsTest {
    private static final int PLAYER = 1, WORLD = 2;
    private static final int GOLD_SHORT = 10_001;

    /** Stands in for a backend: adds up counters, and holds gauges until they are closed. */
    private static final class Backend implements Metrics {
        private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

        private static String key(String name, String... tags) { return name + List.of(tags); }

        @Override public Counter counter(String name, String... tags) {
            return counts.computeIfAbsent(key(name, tags), key -> new LongAdder())::add;
        }

        @Override public Meter counter(String name, LongSupplier count, String... tags) { return gauge(name, count::getAsLong, tags); }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            gauges.put(key(name, tags), value);
            return () -> gauges.remove(key(name, tags));
        }

        long count(String name, String... tags) { return counts.get(key(name, tags)).sum(); }

        double gauge(String name, String... tags) { return gauges.get(key(name, tags)).getAsDouble(); }
    }

    private final Backend backend = new Backend();

    @Test void tasksThatThrowAreCountedByDomain() {
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.striped(1)).domain(WORLD, RouteExecutors.striped(1))
                .errorHandler((context, cause) -> {}).metrics(backend).build();
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        runtime.execute(GameContext.of(PLAYER, 1), context -> { throw new IllegalStateException("boom"); });
        runtime.execute(GameContext.of(WORLD, 1), context -> {});
        runtime.close();
        assertEquals(1, backend.count("managame.runtime.tasks.failed", "domain", "1"));
        assertEquals(0, backend.count("managame.runtime.tasks.failed", "domain", "2"));
    }

    @Test void refusalsAreCountedByCode() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads(1, Duration.ofSeconds(60))).metrics(backend).build();
        runtime.execute(GameContext.of(PLAYER, 1), context -> {
            running.countDown();
            release.await();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        GameRuntimeException full = assertThrows(GameRuntimeException.class, () -> runtime.execute(GameContext.of(PLAYER, 1), context -> {}));
        assertEquals(ROUTE_EXECUTOR_OVERLOADED, full.errorCode());
        release.countDown();
        runtime.close();
        assertThrows(GameRuntimeException.class, () -> runtime.execute(GameContext.of(PLAYER, 1), context -> {}));
        assertEquals(1, backend.count("managame.runtime.tasks.refused", "domain", "1", "code", "ROUTE_EXECUTOR_OVERLOADED"));
        assertEquals(1, backend.count("managame.runtime.tasks.refused", "domain", "1", "code", "RUNTIME_CLOSED"));
    }

    @Test void gaugesReadWhatTheExecutorsHoldUntilClosed() throws Exception {
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).domain(WORLD, RouteExecutors.striped(1))
                .metrics(backend).build();
        for (int domain : List.of(PLAYER, WORLD))
            runtime.execute(GameContext.of(domain, 1), context -> {
                running.countDown();
                release.await();
            });
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        runtime.execute(GameContext.of(PLAYER, 2), context -> release.await());
        runtime.execute(GameContext.of(WORLD, 2), context -> {});
        assertTrue(running.await(5, TimeUnit.SECONDS));

        assertEquals(3, backend.gauge("managame.runtime.tasks.pending", "domain", "1"), "running or waiting, on two keys");
        assertEquals(2, backend.gauge("managame.runtime.routes.max_pending", "domain", "1"), "the fuller key");
        assertEquals(2, backend.gauge("managame.runtime.routes.active", "domain", "1"));
        assertEquals(2, backend.gauge("managame.runtime.tasks.pending", "domain", "2"));
        assertEquals(2, backend.gauge("managame.runtime.routes.max_pending", "domain", "2"), "its one thread");
        assertEquals(0, backend.gauge("managame.runtime.routes.active", "domain", "2"), "a striped domain keeps nothing per key");

        release.countDown();
        runtime.close();
        assertFalse(backend.gauges.containsKey(Backend.key("managame.runtime.tasks.pending", "domain", "1")), "closing lets go of the executors");
    }

    record Buy(int item) {}
    record Broke(int item) {}
    record Boom(int item) {}
    record Later(int item) {}

    @Domain(PLAYER)
    static class Shop {
        final AtomicReference<HandleContext> held = new AtomicReference<>();
        final CountDownLatch holding = new CountDownLatch(1);

        @HandleMethod HandleResult buy(Buy buy) { return HandleResult.ok(buy.item()); }
        @HandleMethod HandleResult broke(Broke broke) { return HandleResult.fail(GOLD_SHORT); }
        @HandleMethod HandleResult boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod void later(HandleContext request, Later later) {
            held.set(request);
            holding.countDown();
        }
    }

    /** A task written as a class of its own, which counts under its own name. */
    static class Settle implements GameTask<GameContext> {
        @Override public void run(GameContext context) {}
    }

    private static final String HANDLER = "managame.runtime.handler.", SHOP = RuntimeMetricsTest.class.getName() + "$Shop#";
    private static final GameContext PLAYER_ONE = GameContext.of(PLAYER, 1, (byte) 1, 1);

    private GameRuntime observed() {
        return GameRuntime.builder().domain(PLAYER, RouteExecutors.striped(1)).errorHandler((context, cause) -> {}).metrics(backend).build();
    }

    private long count(String what, String handler) { return backend.count(HANDLER + what, "handler", handler); }

    @Test void aHandlerReportsHowManyRanAndFailedInTheCodeOrByThrowing() {
        GameRuntime runtime = observed();
        Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(new Shop())).build();
        List<Integer> codes = new CopyOnWriteArrayList<>();
        Consumer<HandleResult> answer = result -> codes.add(result.code());
        dispatcher.dispatch(PLAYER_ONE, new Buy(1), answer);
        dispatcher.dispatch(PLAYER_ONE, new Buy(2), answer);
        dispatcher.dispatch(PLAYER_ONE, new Broke(1), answer);
        dispatcher.dispatch(PLAYER_ONE, new Boom(1), answer);
        runtime.close();

        assertEquals(List.of(0, 0, GOLD_SHORT, RUNTIME_EXECUTION_ERROR), codes);
        assertEquals(2, count("calls", SHOP + "buy"));
        assertEquals(0, count("failed", SHOP + "buy"));
        assertEquals(1, count("business_failed", SHOP + "broke"), "a failure the handler returned");
        assertEquals(0, count("failed", SHOP + "broke"));
        assertEquals(1, count("failed", SHOP + "boom"), "an exception");
        assertEquals(0, count("business_failed", SHOP + "boom"), "the runtime's own code says nothing about the business");
    }

    @Test void howLongTasksWaitedIsReadByDomainUntilClosed() throws Exception {
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        GameRuntime runtime = observed();
        runtime.execute(PLAYER_ONE, context -> release.await());
        runtime.execute(PLAYER_ONE, context -> finished.countDown());
        Thread.sleep(2);
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        await(() -> backend.gauge("managame.runtime.tasks.wait_bucket", "domain", "1", "le", "+Inf") == 2);
        assertTrue(backend.gauge("managame.runtime.tasks.wait_nanos", "domain", "1") >= 2_000_000, "the second waited for the first");
        runtime.close();
        assertFalse(backend.gauges.containsKey(Backend.key("managame.runtime.tasks.wait_nanos", "domain", "1")));
    }

    @Test void aTaskWrittenByHandCountsUnderTheClassThatWroteItAndANamedClassUnderItsOwn() {
        String owner = RuntimeMetricsTest.class.getName();
        GameRuntime runtime = observed();
        runtime.execute(PLAYER_ONE, context -> Thread.sleep(2));
        runtime.execute(PLAYER_ONE, new GameTask<GameContext>() {
            @Override public void run(GameContext context) {}
        });
        runtime.execute(PLAYER_ONE, new Settle());
        runtime.close();

        assertEquals(2, count("calls", owner), "a lambda and an anonymous class");
        assertTrue(count("run_nanos", owner) >= 2_000_000, "how long they ran");
        assertEquals(1, count("calls", owner + "$Settle"));
    }

    @Test void workContinuedOnARequestCountsUnderItsHandler() throws Exception {
        GameRuntime runtime = observed();
        Shop shop = new Shop();
        Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(shop)).build();
        List<Object> answers = new CopyOnWriteArrayList<>();
        dispatcher.dispatch(PLAYER_ONE, new Later(1), result -> answers.add(result.data()));
        assertTrue(shop.holding.await(5, TimeUnit.SECONDS));
        runtime.execute(shop.held.get(), request -> request.reply("done"));
        runtime.close();

        assertEquals(List.of("done"), answers);
        assertEquals(2, count("calls", SHOP + "later"), "the handler, then its continuation");
    }

    record Visit(long other) {}

    @Domain(PLAYER)
    static class Visitor {
        private final GameRuntime runtime;

        Visitor(GameRuntime runtime) { this.runtime = runtime; }

        @HandleMethod void visit(HandleContext request, Visit visit) {
            runtime.call(GameContext.of(PLAYER, visit.other()), () -> "seen", request::reply);
        }
    }

    @Test void bothStepsOfACallCountUnderTheHandlerThatMadeIt() {
        GameRuntime runtime = observed();
        Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(new Visitor(runtime))).build();
        List<Object> answers = new CopyOnWriteArrayList<>();
        dispatcher.dispatch(PLAYER_ONE, new Visit(2), result -> answers.add(result.data()));
        runtime.close();

        assertEquals(List.of("seen"), answers);
        assertEquals(3, count("calls", RuntimeMetricsTest.class.getName() + "$Visitor#visit"), "the handler and its two steps");
    }

    @Domain(PLAYER)
    static class Subscriber {
        @EventMethod void on(Buy event) {}
    }

    static class Jobs {
        final CountDownLatch ran = new CountDownLatch(1);

        @Cron(value = "* * * * * *", domain = PLAYER) void tick() { ran.countDown(); }
    }

    @Test void subscribersAndTimersCountUnderTheirOwnNames() throws Exception {
        GameRuntime runtime = observed();
        EventBus.builder(runtime).register(List.of(new Subscriber())).build().publish(new Buy(1));
        Jobs jobs = new Jobs();
        CountDownLatch fired = new CountDownLatch(1);
        try (GameTimer timer = new GameTimer(runtime)) {
            timer.register(List.of(jobs));
            timer.after(PLAYER_ONE, Duration.ofMillis(1), context -> fired.countDown());
            assertTrue(jobs.ran.await(5, TimeUnit.SECONDS));
            assertTrue(fired.await(5, TimeUnit.SECONDS));
        }
        runtime.close();

        String test = RuntimeMetricsTest.class.getName();
        assertEquals(1, count("calls", test + "$Subscriber#on"));
        assertTrue(count("calls", test + "$Jobs#tick") >= 1);
        assertEquals(1, count("calls", test), "the lambda given to the timer");
    }

    @Domain(PLAYER)
    static class Admin {
        @HttpMethod("/ok") String ok(String body) { return "done"; }
        @HttpMethod("/refused") HttpResult refused(String body) { return HttpResult.fail(GOLD_SHORT, "short of gold"); }
    }

    /** An exchange with no connection behind it. */
    private record Exchange(FullHttpRequest request, String path) implements HttpExchange {
        @Override public boolean respond(FullHttpResponse response) {
            response.release();
            return true;
        }

        @Override public SocketAddress remoteAddress() { return null; }
    }

    @Test void anEndpointReportsItsCallsAndTheFailuresItAnswers() {
        GameRuntime runtime = observed();
        HttpRoutes routes = HttpRoutes.builder(runtime).register(List.of(new Admin())).build();
        for (String path : List.of("/ok", "/refused")) {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, path);
            routes.handle(new Exchange(request, path));
            request.release();
        }
        runtime.close();

        String admin = RuntimeMetricsTest.class.getName() + "$Admin#";
        assertEquals(1, count("calls", admin + "ok"));
        assertEquals(0, count("business_failed", admin + "ok"));
        assertEquals(1, count("business_failed", admin + "refused"));
    }

    @Test void aSlowTaskIsLoggedWithWhatItIsAndWhoseItIs() {
        Logger logger = Logger.getLogger("cn.managame.runtime");
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(capture);
        try {
            GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.striped(1)).slowTask(Duration.ofMillis(1)).build();
            runtime.execute(GameContext.of(PLAYER, 7, (byte) 2, 9), context -> Thread.sleep(5));
            runtime.execute(GameContext.of(PLAYER, 8, (byte) 2, 9), new Settle());
            runtime.close();
        } finally {
            logger.removeHandler(capture);
        }
        assertTrue(logged.stream().anyMatch(line -> line.contains("Slow task") && line.contains("handler=" + RuntimeMetricsTest.class.getName() + ",")
                && line.contains("domain=1") && line.contains("routeKey=7") && line.contains("businessType=2") && line.contains("businessId=9")), logged.toString());
        assertTrue(logged.stream().noneMatch(line -> line.contains("routeKey=8")), "one that was not slow");
    }

    @Test void aSlowTaskThresholdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> GameRuntime.builder().slowTask(Duration.ZERO));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(1);
        }
    }
}
