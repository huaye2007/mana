package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.clock.GameClock;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.diagnostics.CallbackFailureException;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeMetrics;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(20)
class RuntimeArchitectureTest {
    static RuntimeLimits limits(int total, int perRoute, int reserve, int perRouteReserve, int timers) {
        return new RuntimeLimits(total, perRoute, reserve, perRouteReserve, timers, 8, 16);
    }

    record Request(long key) {}
    record Identity(long key) {}
    record Snapshot(int value) {}

    static ProtocolRegistry protocols() {
        return ProtocolRegistry.builder().register(100, ProtocolType.REQUEST, Request.class).build();
    }

    @Handler(routeType = TestRoutes.Player.class)
    static class Chain {
        GameRuntime runtime;
        RuntimeCallback<String> first;
        RuntimeCallback<String> second;
        boolean override;
        @HandlerMethod public void run(Request request) {
            first = runtime.callback(body -> {
                if (override) second = runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Guild.class, 9), next -> {}, null));
                else second = runtime.callback(next -> {});
            });
        }
    }

    @Test void nestedCallbackFailurePreservesRouteAndMetadataWithoutCommandContext() throws Exception {
        var trace = MetadataKey.application(301, String.class);
        for (boolean override : List.of(false, true)) {
            Chain handler = new Chain(); handler.override = override;
            List<HandlerException> errors = new CopyOnWriteArrayList<>();
            try (GameRuntime runtime = builder().protocols(protocols()).defaultRoute(TestRoutes.Player.class, Request.class, Request::key)
                    .handler(handler).exceptionHandler(errors::add).build()) {
                handler.runtime = runtime;
                done(runtime.command(new CommandHandlerInvocation(new Request(1), new Object(), Metadata.empty().with(trace, "nested"))));
                assertTrue(handler.first.onSuccess("first"));
                done(handler.first.completion());
                assertTrue(handler.second.onFail(TestErrorCodes.TIMEOUT));
                ExecutionException completion = assertThrows(ExecutionException.class, () -> done(handler.second.completion()));
                done(runtime.dispatch(handler.second.route().type(), handler.second.route().key(), () -> {}));
                assertEquals(1, errors.size());
                HandlerException failure = errors.getFirst();
                assertSame(failure, completion.getCause());
                assertEquals(TestErrorCodes.TIMEOUT, assertInstanceOf(CallbackFailureException.class, failure.getCause()).code());
                assertEquals(override ? new Route(TestRoutes.Guild.class, 9) : new Route(TestRoutes.Player.class, 1), failure.context().route());
                assertEquals("nested", failure.context().metadata().get(trace));
                assertEquals(HandlerContext.class, failure.context().getClass());
            }
        }
    }

    @Test void crossRuntimeAndEventCallbackFailuresUseTheirOwnExceptionHandlers() throws Exception {
        AtomicReference<RuntimeCallback<String>> crossRuntime = new AtomicReference<>(), eventCallback = new AtomicReference<>();
        List<HandlerException> aErrors = new CopyOnWriteArrayList<>(), bErrors = new CopyOnWriteArrayList<>();
        try (GameRuntime b = builder().exceptionHandler(bErrors::add).build()) {
            @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = EventRoute.class)
            class Events {
                GameRuntime runtime;
                @EventMethod public void event(Request request) { eventCallback.set(runtime.callback(value -> {})); }
            }
            Events events = new Events();
            @Handler(routeType = TestRoutes.Player.class)
            class Command {
                GameRuntime runtime;
                @HandlerMethod public void run(Request request) {
                    runtime.publish(request);
                    crossRuntime.set(b.callback(new CallbackDefinition<>(new Route(TestRoutes.Player.class, 1), value -> {}, null)));
                }
            }
            Command command = new Command();
            try (GameRuntime a = builder().protocols(protocols()).defaultRoute(TestRoutes.Player.class, Request.class, Request::key)
                    .eventType(Request.class).routeKeyResolver(Request.class, new EventRoute())
                    .handler(command).handler(events).exceptionHandler(aErrors::add).build()) {
                command.runtime = a; events.runtime = a;
                done(a.command(new CommandHandlerInvocation(new Request(1), new Object(), Metadata.empty())));
                crossRuntime.get().onFail(5001); eventCallback.get().onFail(5002);
                assertThrows(ExecutionException.class, () -> done(crossRuntime.get().completion()));
                assertThrows(ExecutionException.class, () -> done(eventCallback.get().completion()));
                done(a.dispatch(TestRoutes.Player.class, 1, () -> {})); done(b.dispatch(TestRoutes.Player.class, 1, () -> {}));
                assertEquals(1, aErrors.size()); assertEquals(1, bErrors.size());
                assertEquals(5001, assertInstanceOf(CallbackFailureException.class, bErrors.getFirst().getCause()).code());
                assertEquals(5002, assertInstanceOf(CallbackFailureException.class, aErrors.getFirst().getCause()).code());
                assertEquals(HandlerContext.class, aErrors.getFirst().context().getClass());
                assertEquals(HandlerContext.class, bErrors.getFirst().context().getClass());
            }
        }
    }
    static class EventRoute implements RouteKeyResolver<Request> {
        public long resolve(Request event) { return event.key(); }
    }

    @Handler(routeType = TestRoutes.Player.class)
    static class StatefulCommand {
        volatile int seen;
        @HandlerMethod public void run(Identity id, Request request, Snapshot snapshot) {
            assertEquals(id.key(), HandlerContexts.current().routeKey());
            seen = snapshot.value();
        }
    }

    @Test void onlyIdentityResolvesBeforeQueueAndStateResolvesAfterEarlierTaskCompletes() throws Exception {
        AtomicInteger identityCalls = new AtomicInteger(), stateCalls = new AtomicInteger(), state = new AtomicInteger();
        StatefulCommand handler = new StatefulCommand();
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(Identity.class, invocation -> {
                    assertNull(HandlerContexts.currentOrNull());
                    identityCalls.incrementAndGet(); return new Identity(((Request) invocation.request()).key());
                })
                .register(Snapshot.class, invocation -> {
                    assertEquals(1, HandlerContexts.current().routeKey());
                    stateCalls.incrementAndGet(); return new Snapshot(state.get());
                }).build();
        try (GameRuntime runtime = builder().protocols(protocols()).parameters(parameters)
                .defaultRoute(TestRoutes.Player.class, Identity.class, Identity::key).handler(handler).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                entered.countDown(); await(release); state.set(42);
            });
            RouteTask command;
            try {
                await(entered); command = runtime.command(new Request(1), null);
                assertEquals(1, identityCalls.get()); assertEquals(0, stateCalls.get()); assertEquals(0, handler.seen);
            } finally { release.countDown(); }
            done(first); done(command);
            assertEquals(42, handler.seen); assertEquals(1, identityCalls.get()); assertEquals(1, stateCalls.get());
        }
    }

    @Test void usingAnOrdinaryStateResolverForRoutingFailsAtInitialization() {
        var parameters = ParameterResolverRegistry.builder()
                .register(Identity.class, invocation -> new Identity(1))
                .register(Snapshot.class, invocation -> new Snapshot(0)).build();
        var error = assertThrows(IllegalArgumentException.class, () -> builder().protocols(protocols()).parameters(parameters)
                .defaultRoute(TestRoutes.Player.class, Identity.class, Identity::key).handler(new StatefulCommand()).build());
        assertTrue(error.getMessage().contains("registerRouteSource"));
    }

    @Test void stateResolutionFailureIsOnRouteAndReleasesAdmission() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(Identity.class, invocation -> new Identity(1))
                .register(Snapshot.class, invocation -> {
                    assertEquals(1, HandlerContexts.current().routeKey());
                    throw new IllegalStateException("state resolution");
                }).build();
        try (GameRuntime runtime = builder().protocols(protocols()).parameters(parameters)
                .defaultRoute(TestRoutes.Player.class, Identity.class, Identity::key).handler(new StatefulCommand())
                .limits(limits(1, 1, 0, 0, 1)).exceptionHandler(errors::add).build()) {
            RouteTask task = runtime.command(new Request(1), null);
            assertThrows(ExecutionException.class, () -> done(task));
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(1, errors.size()); assertEquals(1, errors.getFirst().context().routeKey());
            assertEquals(0, runtime.metrics().outstandingTasks()); assertEquals(1, runtime.metrics().failedTasks());
        }
    }

    @Test void callbackReserveSurvivesNormalSaturationAndRejectedCallbackCanRetry() throws Exception {
        AtomicInteger invoked = new AtomicInteger();
        try (GameRuntime runtime = builder().limits(limits(2, 2, 1, 1, 10)).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            RouteTask second;
            RuntimeCallback<String> accepted = runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Player.class, 1),
                    body -> invoked.incrementAndGet(), null));
            RuntimeCallback<String> retry = runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Player.class, 1),
                    body -> invoked.incrementAndGet(), null));
            try {
                await(entered);
                second = runtime.dispatch(TestRoutes.Player.class, 1, () -> {});
                var routeFull = assertThrows(RuntimeOverloadedException.class,
                        () -> runtime.dispatch(TestRoutes.Player.class, 1, () -> fail("Rejected task ran")));
                assertEquals(RuntimeOverloadedException.Reason.ROUTE_CAPACITY, routeFull.reason());
                var globalFull = assertThrows(RuntimeOverloadedException.class,
                        () -> runtime.dispatch(TestRoutes.Player.class, 2, () -> fail("Rejected task ran")));
                assertEquals(RuntimeOverloadedException.Reason.GLOBAL_CAPACITY, globalFull.reason());
                assertTrue(accepted.onSuccess("body"));
                assertEquals(3, runtime.metrics().outstandingTasks());
                HandlerException failed = assertThrows(HandlerException.class, () -> retry.onSuccess("retry"));
                assertInstanceOf(RuntimeOverloadedException.class, failed.getCause());
                assertFalse(retry.isCompleted()); assertEquals(0, invoked.get());
            } finally { release.countDown(); }
            done(first); done(second);
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(1, invoked.get());
            assertTrue(retry.onSuccess("retry"));
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(2, invoked.get()); assertTrue(retry.isCompleted());
            assertEquals(0, runtime.metrics().outstandingTasks());
            assertEquals(3, runtime.metrics().rejectedTasks());
        }
    }

    @Test void concurrentAdmissionAcrossShardsCannotExceedGlobalLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (GameRuntime runtime = builder().limits(limits(8, 2, 0, 0, 10)).build();
             ExecutorService senders = Executors.newFixedThreadPool(16)) {
            List<Future<RouteTask>> submissions = new ArrayList<>();
            List<RouteTask> accepted = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) {
                    int key = i;
                    submissions.add(senders.submit(() -> {
                        try { return runtime.dispatch(TestRoutes.Player.class, key, () -> await(release)); }
                        catch (RuntimeOverloadedException expected) { return null; }
                    }));
                }
                for (var submission : submissions) {
                    RouteTask task = submission.get(5, TimeUnit.SECONDS);
                    if (task != null) accepted.add(task);
                }
                assertEquals(8, accepted.size()); assertEquals(8, runtime.metrics().outstandingTasks());
                assertEquals(56, runtime.metrics().rejectedTasks());
            } finally { release.countDown(); }
            for (RouteTask task : accepted) done(task);
            assertEquals(0, runtime.metrics().outstandingTasks()); assertEquals(8, runtime.metrics().completedTasks());
        }
    }

    @Test void metricsCountFailuresAndMonotonicDurations() throws Exception {
        try (GameRuntime runtime = builder().build()) {
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {}));
            RouteTask failure = runtime.dispatch(TestRoutes.Guild.class, 1, () -> { throw new IllegalArgumentException(); });
            assertThrows(ExecutionException.class, () -> done(failure));
            RuntimeMetrics metrics = runtime.metrics();
            assertEquals(1, metrics.completedTasks()); assertEquals(1, metrics.failedTasks());
            assertEquals(0, metrics.outstandingTasks()); assertEquals(0, metrics.rejectedTasks());
            assertTrue(metrics.totalQueueNanos() >= metrics.maxQueueNanos());
            assertTrue(metrics.totalExecutionNanos() >= metrics.maxExecutionNanos());
            assertTrue(metrics.maxExecutionNanos() > 0);
        }
    }

    @Test void boundedTimersReleaseCapacityOnCancelAndDispatchAndRespectPollBatch() throws Exception {
        var clock = SchedulerAndCallbackTest.clock();
        RuntimeLimits limits = new RuntimeLimits(100, 100, 0, 0, 3, 4, 1);
        AtomicInteger calls = new AtomicInteger();
        try (GameRuntime runtime = builder().clock(clock).limits(limits).build()) {
            TimerTask cancelled = runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, calls::incrementAndGet);
            runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, calls::incrementAndGet);
            runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, calls::incrementAndGet);
            var full = assertThrows(RuntimeOverloadedException.class,
                    () -> runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, () -> {}));
            assertEquals(RuntimeOverloadedException.Reason.TIMER_CAPACITY, full.reason());
            assertTrue(cancelled.cancel());
            runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, calls::incrementAndGet);
            assertEquals(3, runtime.metrics().scheduledTimers());
            assertEquals(1, runtime.runDueTimers()); assertEquals(2, runtime.metrics().scheduledTimers());
            assertEquals(1, runtime.runDueTimers()); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {}));
            assertEquals(3, calls.get()); assertEquals(0, runtime.metrics().scheduledTimers());
            assertEquals(1, runtime.metrics().rejectedTasks());
        }
    }

    @Test void schedulerDoesNotHoldQueueLockWhileCallingExceptionObserver() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<GameRuntime> reference = new AtomicReference<>();
        AtomicBoolean observed = new AtomicBoolean();
        try (ExecutorService external = Executors.newSingleThreadExecutor();
             GameRuntime runtime = builder().limits(limits(1, 1, 0, 0, 3)).exceptionHandler(error -> {
                 try {
                     TimerTask created = external.submit(() -> reference.get().schedule(TestRoutes.Guild.class, 2, Duration.ofDays(1), () -> {}))
                             .get(2, TimeUnit.SECONDS);
                     assertTrue(created.cancel());
                     assertEquals(0, reference.get().runDueTimers()); // Nonblocking reentrant poll.
                     observed.set(true);
                 } catch (Exception failure) { throw new AssertionError(failure); }
             }).build()) {
            reference.set(runtime);
            RouteTask blocked = runtime.dispatch(TestRoutes.Guild.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, () -> fail("Full route must reject timer"));
                assertEquals(1, runtime.runDueTimers());
                assertTrue(observed.get());
            } finally { release.countDown(); }
            done(blocked); assertEquals(0, runtime.metrics().outstandingTasks());
        }
    }

    static class CronJob {
        final AtomicInteger calls = new AtomicInteger();
        @Cron(value = "* * * * * ?", routeType = TestRoutes.Guild.class, routeKey = 1)
        public void tick() { calls.incrementAndGet(); }
    }

    @Test void cronKeepsItsCapacityReservationWhileCalculatingNextOccurrenceOutsideLock() throws Exception {
        var base = SchedulerAndCallbackTest.clock();
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch calculating = new CountDownLatch(1), release = new CountDownLatch(1);
        GameClock clock = new GameClock() {
            public ZoneId zoneId() { return base.zoneId(); }
            public Instant now() {
                if (armed.get() && reads.incrementAndGet() == 2) {
                    calculating.countDown(); await(release);
                }
                return base.now();
            }
        };
        CronJob cron = new CronJob();
        try (GameRuntime runtime = builder().clock(clock).handler(cron).limits(limits(10, 10, 0, 0, 1)).build();
             ExecutorService external = Executors.newSingleThreadExecutor()) {
            base.advance(Duration.ofSeconds(1)); armed.set(true);
            Future<Integer> poll = external.submit(runtime::runDueTimers);
            try {
                await(calculating);
                assertEquals(1, runtime.metrics().scheduledTimers());
                assertThrows(RuntimeOverloadedException.class,
                        () -> runtime.schedule(TestRoutes.Guild.class, 1, Duration.ofDays(1), () -> {}));
            } finally { release.countDown(); }
            assertEquals(1, poll.get(5, TimeUnit.SECONDS));
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {}));
            assertEquals(1, cron.calls.get()); assertEquals(1, runtime.metrics().scheduledTimers());
            base.advance(Duration.ofSeconds(1)); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {})); assertEquals(2, cron.calls.get());
        }
    }

    @Test void interruptionInOneTaskDoesNotPoisonNextTaskInItsRoute() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = builder().build()) {
            RouteTask first = runtime.dispatch(TestRoutes.Guild.class, 1, () -> {
                entered.countDown(); await(release); Thread.currentThread().interrupt();
            });
            RouteTask second;
            try {
                await(entered);
                second = runtime.dispatch(TestRoutes.Guild.class, 1, () -> assertFalse(Thread.currentThread().isInterrupted()));
            } finally { release.countDown(); }
            done(first); done(second);
        }
    }

    @Test void invalidCapacitiesAndExcessiveCronRegistrationsFailDuringInitialization() {
        assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(Integer.MAX_VALUE, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(1, 1, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> builder().handler(new CronJob()).handler(new CronJob())
                .limits(limits(10, 10, 0, 0, 1)).build());
    }

    @Test void independentRoutesPreserveFifoAcrossConcurrentShardsAndIdleRecreation() throws Exception {
        try (GameRuntime runtime = builder().build(); ExecutorService producers = Executors.newFixedThreadPool(8)) {
            List<Future<List<RouteTask>>> submitted = new ArrayList<>();
            for (int routeIndex = 0; routeIndex < 32; routeIndex++) {
                int key = routeIndex;
                submitted.add(producers.submit(() -> {
                    AtomicInteger sequence = new AtomicInteger();
                    List<RouteTask> tasks = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        int expected = i;
                        tasks.add(runtime.dispatch(TestRoutes.ALL.get(key % TestRoutes.ALL.size()), key / 4, () -> {
                            assertEquals(expected, sequence.getAndIncrement());
                            Thread.yield();
                        }));
                    }
                    return tasks;
                }));
            }
            for (var producer : submitted) for (RouteTask task : producer.get()) done(task);
            assertEquals(3200, runtime.metrics().completedTasks());
            assertEquals(0, runtime.metrics().outstandingTasks());
        }
    }

    @Test void concurrentShutdownDrainsEveryAcceptedSubmissionWithoutLeakingCapacity() throws Exception {
        GameRuntime runtime = builder().build();
        AtomicInteger executed = new AtomicInteger(), accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newFixedThreadPool(9)) {
            for (int i = 0; i < 50; i++) {
                runtime.dispatch(TestRoutes.Account.class, i % 8, executed::incrementAndGet);
                accepted.incrementAndGet();
            }
            List<Future<?>> senders = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int key = i;
                senders.add(callers.submit(() -> {
                    await(start);
                    for (int j = 0; j < 100; j++) {
                        try {
                            runtime.dispatch(TestRoutes.Account.class, key, executed::incrementAndGet);
                            accepted.incrementAndGet();
                        } catch (RejectedExecutionException expected) { /* Shutdown won admission. */ }
                    }
                }));
            }
            Future<?> close = callers.submit(() -> { await(start); runtime.close(); });
            start.countDown();
            for (Future<?> sender : senders) sender.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(accepted.get(), executed.get());
            assertEquals(0, runtime.metrics().outstandingTasks());
            assertEquals(0, runtime.activeRoutes());
        } finally { runtime.close(); }
    }

    @Test void overloadedCronIsReportedAndItsNextOccurrenceStillRuns() throws Exception {
        var clock = SchedulerAndCallbackTest.clock();
        CronJob cron = new CronJob();
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRuntime runtime = builder().clock(clock).handler(cron).limits(limits(1, 1, 0, 0, 1))
                .exceptionHandler(errors::add).build()) {
            RouteTask blocker = runtime.dispatch(TestRoutes.Guild.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered); clock.advance(Duration.ofSeconds(1));
                assertEquals(1, runtime.runDueTimers()); assertEquals(1, errors.size());
                assertInstanceOf(RuntimeOverloadedException.class, errors.getFirst().getCause());
                assertEquals(1, runtime.metrics().scheduledTimers()); assertEquals(0, cron.calls.get());
            } finally { release.countDown(); }
            done(blocker); clock.advance(Duration.ofSeconds(1)); assertEquals(1, runtime.runDueTimers());
            // The cron itself occupies the only normal slot until it finishes.
            runtime.close();
            assertEquals(1, cron.calls.get()); assertEquals(0, runtime.metrics().scheduledTimers());
        }
    }
}
