package cn.managame.runtime;

import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.diagnostic.DomainStats;
import cn.managame.runtime.diagnostic.RunningTask;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Naming the Route a stuck instance is stuck on. Counting running work says an instance is busy; it never
 * says which player, guild or room is holding a Route, which is the question an operator actually has.
 */
class RunningTaskTest {
    record Work(Runnable action) {}

    @Handler(domain = 1)
    static class Handlers {
        @HandlerMethod public void player(Work work) { work.action().run(); }
        @HandlerMethod(domain = 2) public void guild(Guild work) { work.action().run(); }
    }
    record Guild(Runnable action) {}

    /** Domain 1 alone, for the Runtime below that registers only Domain 1. */
    @Handler(domain = 1)
    static class PlayerOnly {
        @HandlerMethod public void player(Work work) { work.action().run(); }
    }

    private final List<RuntimeError> errors = new ArrayList<>();

    private GameRuntime runtime() {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1, 2)))
                .protocols(ProtocolRegistry.of(ProtocolDescriptor.request(1, Work.class),
                        ProtocolDescriptor.request(2, Guild.class)))
                .handlers(List.of(new Handlers())).errorHandler(errors::add).build();
    }

    private static DomainStats of(GameRuntime runtime, int domain) {
        return runtime.stats().domains().stream().filter(d -> d.routeDomain() == domain).findFirst().orElseThrow();
    }

    @Test void nothingRunningReportsNoRouteAtAll() {
        try (var runtime = runtime()) {
            assertSame(RunningTask.none(), runtime.stats().oldestRunning());
            // A Key of 0 is never valid, so "none" cannot be mistaken for a real Route.
            assertEquals(0, runtime.stats().oldestRunning().routeKey());
            for (DomainStats domain : runtime.stats().domains())
                assertSame(RunningTask.none(), domain.oldestRunning());
        }
    }

    @Test void aBlockedHandlerIsNamedByItsDomainAndKeyWhileItRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = runtime()) {
            runtime.dispatch(new DefaultHandlerContext(1, 10001L, new Work(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            RunningTask stuck = runtime.stats().oldestRunning();
            assertEquals(1, stuck.routeDomain());
            assertEquals(10001L, stuck.routeKey());
            assertTrue(stuck.runningNanos() > 0, "a running task must report a positive age");
            // The age is execution time, so a second reading of the same stuck task reports more of it.
            Thread.sleep(5);
            assertTrue(runtime.stats().oldestRunning().runningNanos() > stuck.runningNanos());

            // The same Route is named under its own Domain, and the other Domain reports nothing.
            assertEquals(10001L, of(runtime, 1).oldestRunning().routeKey());
            assertSame(RunningTask.none(), of(runtime, 2).oldestRunning());

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.stats().oldestRunning() != RunningTask.none() && System.nanoTime() < deadline)
                Thread.onSpinWait();
            // The slot is given back, so the reading stops meaning anything the moment the task finishes.
            assertSame(RunningTask.none(), runtime.stats().oldestRunning());
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    @Test void theOldestWinsAcrossDomainsAndEachDomainStillNamesItsOwn() throws Exception {
        var firstIn = new CountDownLatch(1);
        var secondIn = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Runnable block = () -> {
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        try (var runtime = runtime()) {
            runtime.dispatch(new DefaultHandlerContext(1, 7L, new Work(() -> { firstIn.countDown(); block.run(); })));
            assertTrue(firstIn.await(5, TimeUnit.SECONDS));
            Thread.sleep(5);   // so the two start times cannot be equal
            runtime.dispatch(new DefaultHandlerContext(2, 9L, new Guild(() -> { secondIn.countDown(); block.run(); })));
            assertTrue(secondIn.await(5, TimeUnit.SECONDS));

            // Both are running; the Runtime reading is the older of the two, not whichever was seen last.
            RunningTask overall = runtime.stats().oldestRunning();
            assertEquals(1, overall.routeDomain());
            assertEquals(7L, overall.routeKey());
            assertEquals(7L, of(runtime, 1).oldestRunning().routeKey());
            assertEquals(9L, of(runtime, 2).oldestRunning().routeKey());
            assertTrue(of(runtime, 1).oldestRunning().runningNanos() > of(runtime, 2).oldestRunning().runningNanos());

            release.countDown();
        }
    }

    @Test void theOldestOfOneDomainIsTheOldestRouteNotTheBusiestOne() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable block = () -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        try (var runtime = runtime()) {
            runtime.dispatch(new DefaultHandlerContext(1, 100L, new Work(block)));
            Thread.sleep(5);
            runtime.dispatch(new DefaultHandlerContext(1, 200L, new Work(block)));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            // Two Routes of one Domain are stuck; the reading names the one that has been stuck longest.
            assertEquals(100L, of(runtime, 1).oldestRunning().routeKey());
            assertEquals(2, of(runtime, 1).running());
            release.countDown();
        }
    }

    @Test void theReadingSurvivesTimingBeingTurnedOff() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
                .protocols(ProtocolRegistry.of(ProtocolDescriptor.request(1, Work.class)))
                .handlers(List.of(new PlayerOnly())).errorHandler(errors::add)
                .executionTiming(false).build()) {
            runtime.dispatch(new DefaultHandlerContext(1, 55L, new Work(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // executionTiming only drops the aggregate; which Route is stuck is the reading you still need.
            assertEquals(0, runtime.stats().executionNanos());
            assertEquals(55L, runtime.stats().oldestRunning().routeKey());
            assertTrue(runtime.stats().oldestRunning().runningNanos() > 0);
            release.countDown();
        }
    }
}
