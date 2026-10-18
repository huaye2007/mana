package cn.managame.runtime;

import cn.managame.core.Metadata;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.diagnostic.DomainStats;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutorStats;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.executor.VirtualThreadRouteExecutor;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static cn.managame.core.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_CLOSED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-Domain readings and the executor limits they are judged against: a rising queue means nothing
 * without the capacity it is approaching, and a rising rejection count means nothing without a Domain.
 */
class DomainStatsTest {
    record Player(long userId) {}
    record Guild(long userId) {}

    @Handler(domain = 1, routeKey = "userId")
    static class Handlers {
        @HandlerMethod public void player(Player message) {}
        @HandlerMethod(domain = 2) public void guild(Guild message) {}
    }

    /** Admits into a queue the test drains by hand, so queued work can be observed before it runs. */
    static final class Manual implements RouteExecutor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        RouteExecuteStatus status = RouteExecuteStatus.ACCEPTED;
        @Override public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
            if (status == RouteExecuteStatus.ACCEPTED) tasks.add(task);
            return status;
        }
        @Override public RouteExecutorStats stats() { return new RouteExecutorStats(64, 8, tasks.size()); }
    }

    private final List<RuntimeError> errors = new ArrayList<>();

    private GameRuntimeBuilder builder(RouteExecutor executor) {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1, 2)))
                .protocols(ProtocolRegistry.of(
                        ProtocolDescriptor.request(1, Player.class),
                        ProtocolDescriptor.request(2, Guild.class)))
                .handlers(List.of(new Handlers())).errorHandler(errors::add);
    }

    private static DomainStats of(GameRuntime runtime, int domain) {
        return runtime.stats().domains().stream().filter(d -> d.routeDomain() == domain).findFirst().orElseThrow();
    }

    @Test void everyRegisteredDomainIsReportedInOrderEvenBeforeItDoesAnything() {
        try (var runtime = builder(new Manual()).build()) {
            assertEquals(List.of(1, 2), runtime.stats().domains().stream().map(DomainStats::routeDomain).toList());
            runtime.stats().domains().forEach(domain -> {
                assertEquals(0, domain.queued()); assertEquals(0, domain.completed()); assertEquals(0, domain.rejected());
            });
        }
    }

    @Test void workIsCountedAgainstItsOwnDomainAndNotTheOther() {
        var executor = new Manual();
        try (var runtime = builder(executor).build()) {
            runtime.dispatch(null, 7L, new Player(7));
            runtime.dispatch(null, 7L, new Player(7));
            runtime.dispatch(null, 9L, new Guild(9));

            assertEquals(2, of(runtime, 1).queued()); assertEquals(1, of(runtime, 2).queued());
            assertEquals(3, runtime.stats().queued());

            while (!executor.tasks.isEmpty()) executor.tasks.remove().run();
            assertEquals(0, of(runtime, 1).queued()); assertEquals(2, of(runtime, 1).completed());
            assertEquals(1, of(runtime, 2).completed());
            assertEquals(3, runtime.stats().completed());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void aRejectionNamesTheDomainItWasRefusedOn() {
        var executor = new Manual();
        try (var runtime = builder(executor).build()) {
            executor.status = RouteExecuteStatus.OVERLOADED;
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(null, 9L, new Guild(9))).errorCode());

            assertEquals(0, of(runtime, 1).rejected());
            assertEquals(1, of(runtime, 2).rejected());
            assertEquals(1, runtime.stats().rejected());
        }
    }

    @Test void aClosureRefusalIsAttributedWhenTheDomainIsAlreadyKnown() {
        var executor = new Manual();
        var runtime = builder(executor).build();
        runtime.close();
        // The explicit-context entry validates a Domain before admission, so the refusal belongs to it.
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                () -> runtime.dispatch(new DefaultHandlerContext(2, 9L, 0, 0, Metadata.empty(), new Guild(9))))
                .errorCode());
        assertEquals(1, of(runtime, 2).rejected());
        assertEquals(0, of(runtime, 1).rejected());

        // The convenience entry rejects before any Handler has named a Domain, so only the Runtime counts it.
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                () -> runtime.dispatch(null, 7L, new Player(7))).errorCode());
        assertEquals(0, of(runtime, 1).rejected());
        assertEquals(2, runtime.stats().rejected());
    }

    @Test void eachDomainCarriesTheLimitsOfTheExecutorBoundToIt() {
        var executor = new Manual();
        try (var runtime = builder(executor).build()) {
            runtime.dispatch(null, 7L, new Player(7));
            // One executor serves both Domains, so both repeat its numbers rather than splitting them.
            for (int domain : List.of(1, 2)) {
                assertEquals(64, of(runtime, domain).executor().capacity());
                assertEquals(8, of(runtime, domain).executor().routeCapacity());
                assertEquals(1, of(runtime, domain).executor().activeRoutes());
            }
        }
    }

    @Test void theOfficialExecutorsReportTheirConfiguredBounds() {
        try (var virtual = new VirtualThreadRouteExecutor(4096, 32)) {
            assertEquals(4096, virtual.stats().capacity());
            assertEquals(32, virtual.stats().routeCapacity());
            assertEquals(0, virtual.stats().activeRoutes());
        }
        try (var striped = RouteExecutors.platformThreads(4)) {
            // Routes share stripes, so the total is a bound and there is no per-Route limit to report.
            assertEquals(4L * 65536, striped.stats().capacity());
            assertEquals(0, striped.stats().routeCapacity());
            assertEquals(0, striped.stats().activeRoutes());
        }
    }

    @Test void anExecutorThatRefusesToReportDoesNotBreakAReading() {
        RouteExecutor hostile = new RouteExecutor() {
            @Override public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
                return RouteExecuteStatus.ACCEPTED;
            }
            @Override public RouteExecutorStats stats() { throw new IllegalStateException("no readings"); }
        };
        try (var runtime = builder(hostile).build()) {
            var stats = runtime.stats();
            assertEquals(2, stats.domains().size());
            stats.domains().forEach(domain -> assertEquals(0, domain.executor().capacity()));
        }
    }
}
