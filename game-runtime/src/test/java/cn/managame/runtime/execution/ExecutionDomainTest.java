package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(20)
class ExecutionDomainTest {
    static DomainLimits capacity(int tasks, int reserve) {
        return new DomainLimits(tasks, tasks, reserve, reserve);
    }
    @Test void hotEntityYieldsToAnotherEntityWithOneWorker() throws Exception {
        var spec = ExecutionDomain.platform("gameplay").threads(1).tasksPerTurn(1).build();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, spec).build()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                order.add("A1"); entered.countDown(); await(release);
            });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(TestRoutes.Player.class, 1, () -> order.add("A2"));
                RouteTask third = runtime.dispatch(TestRoutes.Player.class, 1, () -> order.add("A3"));
                RouteTask other = runtime.dispatch(TestRoutes.Player.class, 2, () -> order.add("B"));
                release.countDown();
                done(first); done(second); done(third); done(other);
                assertEquals(List.of("A1", "B", "A2", "A3"), order);
            } finally { release.countDown(); }
        }
    }
    @Test void busyEntityCanMoveBetweenPlatformWorkersWithoutOverlap() throws Exception {
        var spec = ExecutionDomain.platform("battle").threads(2).tasksPerTurn(1).build();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Battle.class, spec).build()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var thread = new AtomicReference<Thread>(); var active = new AtomicInteger();
            RouteTask first = runtime.dispatch(TestRoutes.Battle.class, 7, () -> {
                assertEquals(1, active.incrementAndGet());
                thread.set(Thread.currentThread()); entered.countDown(); await(release);
                active.decrementAndGet();
            });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(TestRoutes.Battle.class, 7, () -> {
                    assertEquals(1, active.incrementAndGet());
                    assertFalse(Thread.currentThread().isVirtual());
                    assertTrue(Thread.currentThread().getName().startsWith("game-battle-"));
                    assertNotSame(thread.get(), Thread.currentThread());
                    active.decrementAndGet();
                });
                release.countDown(); done(first); done(second);
            } finally { release.countDown(); }
        }
    }
    @Test void loginCapacityAndVirtualConcurrencyDoNotConsumeBattleResources() throws Exception {
        var login = ExecutionDomain.virtual("login").maxConcurrentRoutes(1).tasksPerTurn(1).maxTasks(2).maxTasksPerRoute(2).callbackReserve(1, 1).build();
        var battle = ExecutionDomain.platform("battle").threads(1).maxTasks(1).maxTasksPerRoute(1).callbackReserve(0, 0).build();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondRan = new AtomicBoolean(); var callbackRan = new AtomicBoolean();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Account.class, login)
                .executionDomain(TestRoutes.Battle.class, battle).build()) {
            RouteTask first = runtime.dispatch(TestRoutes.Account.class, 1, () -> {
                assertTrue(Thread.currentThread().isVirtual()); entered.countDown(); await(release);
            });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(TestRoutes.Account.class, 2, () -> secondRan.set(true));
                assertFalse(secondRan.get());
                var rejected = assertThrows(RuntimeOverloadedException.class,
                        () -> runtime.dispatch(TestRoutes.Account.class, 3, () -> fail("Must not run")));
                assertEquals(RuntimeOverloadedException.Reason.DOMAIN_CAPACITY, rejected.reason());
                var callback = runtime.callback(new CallbackDefinition<String>(new Route(TestRoutes.Account.class, 1),
                        ignored -> callbackRan.set(true), null));
                assertTrue(callback.onSuccess("loaded"));
                done(runtime.dispatch(TestRoutes.Battle.class, 1, () -> {
                    assertFalse(Thread.currentThread().isVirtual());
                    assertTrue(Thread.currentThread().getName().startsWith("game-battle-"));
                }));
                assertFalse(secondRan.get()); assertFalse(callbackRan.get());
                assertEquals(3, runtime.executionDomains().get("login").tasks().outstandingTasks());
                release.countDown(); done(first); done(second);
            } finally { release.countDown(); }
        }
        assertTrue(callbackRan.get());
    }
    @Test void sharedDomainSerializesEntitiesAndDrainsAllBatchesOnClose() throws Exception {
        for (boolean virtual : List.of(false, true)) {
            var spec = (virtual ? ExecutionDomain.virtual("shared").maxConcurrentRoutes(2)
                    : ExecutionDomain.platform("shared").threads(2)).tasksPerTurn(3).build();
            GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, spec)
                    .executionDomain(TestRoutes.Guild.class, spec).build();
            List<RouteTask> tasks = new ArrayList<>();
            var counts = new ConcurrentHashMap<Route, AtomicInteger>();
            try (ExecutorService senders = Executors.newFixedThreadPool(8)) {
                List<Future<List<RouteTask>>> results = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    Route route = new Route(i % 2 == 0 ? TestRoutes.Player.class : TestRoutes.Guild.class, i / 2);
                    counts.put(route, new AtomicInteger());
                    results.add(senders.submit(() -> {
                        List<RouteTask> submitted = new ArrayList<>();
                        for (int j = 0; j < 100; j++) {
                            int expected = j;
                            submitted.add(runtime.dispatch(route.type(), route.key(), () -> {
                                assertEquals(expected, counts.get(route).getAndIncrement());
                                assertEquals(virtual, Thread.currentThread().isVirtual());
                            }));
                        }
                        return submitted;
                    }));
                }
                for (var result : results) tasks.addAll(result.get(5, TimeUnit.SECONDS));
                runtime.close();
                for (RouteTask task : tasks) done(task);
                assertEquals(3200, runtime.metrics().completedTasks());
                assertEquals(0, runtime.metrics().outstandingTasks()); assertEquals(0, runtime.activeRoutes());
                assertEquals(1, runtime.executionDomains().size());
                assertEquals(0, runtime.executionDomains().get("shared").runningBatches());
            } finally { runtime.close(); }
        }
    }
    interface GuildRequest { long guild(); }
    record Reordered(long guild) implements GuildRequest {}
    record Hidden(long guild) implements GuildRequest {}
    record GuildId(long value) {}
    record ActorId(long value) {}
    @Handler(routeType = TestRoutes.Guild.class)
    static class GuildHandler {
        final List<Long> visited = new ArrayList<>();
        @HandlerMethod public void reordered(Reordered request, ActorId actor, GuildId guild) {
            assertEquals(request.guild(), guild.value()); record(actor);
        }
        @HandlerMethod public void hidden(ActorId actor, Hidden request) { record(actor); }
        private void record(ActorId actor) {
            assertEquals(123, actor.value()); assertFalse(Thread.currentThread().isVirtual());
            visited.add(HandlerContexts.current().routeKey());
        }
    }
    @Test void routeIdentityIsIndependentOfParameterPositionAndCanBeOmitted() throws Exception {
        var identities = new AtomicInteger();
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(GuildId.class, invocation -> {
                    assertNull(HandlerContexts.currentOrNull()); identities.incrementAndGet();
                    return new GuildId(((GuildRequest) invocation.request()).guild());
                })
                .register(ActorId.class, invocation -> {
                    assertSame(TestRoutes.Guild.class, HandlerContexts.current().routeType());
                    return new ActorId(123);
                }).build();
        var handler = new GuildHandler();
        try (GameRuntime runtime = builder().parameters(parameters)
                .protocols(ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, Reordered.class)
                        .register(2, ProtocolType.REQUEST, Hidden.class).build())
                .defaultRoute(TestRoutes.Guild.class, GuildId.class, GuildId::value)
                .executionDomain(TestRoutes.Guild.class, ExecutionDomain.platform("guild").threads(2).build())
                .handler(handler).build()) {
            done(runtime.command(new Reordered(777), null)); done(runtime.command(new Hidden(888), null));
            assertEquals(List.of(777L, 888L), handler.visited); assertEquals(2, identities.get());
        }
    }
    @Test void explicitMappingsFailEarlyAndSpecificationsDoNotShareWorkersAcrossRuntimes() throws Exception {
        var spec = ExecutionDomain.platform("isolated").threads(1).build();
        assertThrows(IllegalArgumentException.class, () -> builder()
                .executionDomain(TestRoutes.Player.class, spec).executionDomain(TestRoutes.Player.class, spec));
        assertThrows(IllegalArgumentException.class, () -> builder().executionDomain(TestRoutes.Player.class, spec)
                .executionDomain(TestRoutes.Guild.class, ExecutionDomain.platform("isolated").build()).build());
        assertThrows(IllegalArgumentException.class, () -> HandlerBindingTest.single()
                .handler(new HandlerBindingTest.Inherited()).executionDomain(TestRoutes.Player.class, spec).build());
        try (GameRuntime first = builder().executionDomain(TestRoutes.Player.class, spec).build();
             GameRuntime second = builder().executionDomain(TestRoutes.Player.class, spec).build()) {
            assertThrows(IllegalArgumentException.class, () -> first.dispatch(TestRoutes.Battle.class, 1, () -> {}));
            assertThrows(IllegalArgumentException.class,
                    () -> first.schedule(TestRoutes.Battle.class, 1, Duration.ZERO, () -> {}));
            first.close(); done(second.dispatch(TestRoutes.Player.class, 1, () -> {}));
        }
    }

    @Test void platformAdmissionRacingCloseNeverLosesAcceptedTasks() throws Exception {
        var spec = ExecutionDomain.platform("closing").threads(2).tasksPerTurn(1).build();
        GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, spec).build();
        var accepted = new AtomicInteger(); var executed = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 100; i++) {
                runtime.dispatch(TestRoutes.Player.class, i % 4, executed::incrementAndGet);
                accepted.incrementAndGet();
            }
            List<Future<?>> senders = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int key = i;
                senders.add(callers.submit(() -> {
                    await(start);
                    for (int j = 0; j < 100; j++) {
                        try {
                            runtime.dispatch(TestRoutes.Player.class, key, executed::incrementAndGet);
                            accepted.incrementAndGet();
                        } catch (RejectedExecutionException expected) { }
                    }
                }));
            }
            Future<?> closing = callers.submit(() -> { await(start); runtime.close(); });
            start.countDown();
            for (var sender : senders) sender.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(accepted.get(), executed.get());
            assertEquals(0, runtime.activeRoutes());
            assertEquals(0, runtime.metrics().outstandingTasks());
        } finally { runtime.close(); }
    }
}
