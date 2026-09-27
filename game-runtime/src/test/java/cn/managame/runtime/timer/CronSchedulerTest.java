package cn.managame.runtime.timer;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.TimerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.time.GameTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import static cn.managame.core.FrameworkErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("GameTime")
class CronSchedulerTest {
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final List<RuntimeError> errors = new CopyOnWriteArrayList<>();
    private GameRuntime runtime;

    static class Job {
        final AtomicInteger calls = new AtomicInteger();
        @Cron(value = "0 * * * * ?", domain = 1, routeKey = 42)
        public void tick() {
            assertInstanceOf(TimerContext.class, Contexts.current());
            assertEquals(42, Contexts.current().routeKey());
            calls.incrementAndGet();
        }
    }
    static class InheritedJob extends Job {}

    @BeforeEach void freeze() { atMinuteStart(); }
    @AfterEach void close() {
        if (runtime != null) runtime.close();
        GameTime.resetClock();
    }
    private static void atMinuteStart() { set("2026-09-25T00:00:00Z"); }
    private static void nearMinuteEnd() { set("2026-09-25T00:00:59.990Z"); }
    private static void set(String instant) {
        GameTime.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
    private GameRuntimeBuilder builder(RouteExecutor executor, Object... jobs) {
        return GameRuntimeBuilder.builder()
            .routeDomains(List.of(RouteDomain.of(1, "test")))
            .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)))
            .cronHandlers(List.of(jobs)).errorHandler(errors::add);
    }
    private void build(Object... jobs) {
        runtime = builder((domain, key, task) -> {
            queue.add(task); return RouteExecuteStatus.ACCEPTED;
        }, jobs).build();
    }
    private Runnable next() throws Exception {
        Runnable task = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "Cron did not submit a Route task");
        return task;
    }
    private void noTask() throws Exception {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS), "Unexpected extra Cron invocation");
    }

    @Test void changingGameClockNeedsExplicitRescheduleAndDoesNotChangeDynamicTimer() throws Exception {
        Job job = new Job();
        build(job); // next Cron is sixty real seconds away
        TimerRef far = runtime.timer().schedule(1, 1, Duration.ofDays(1), () -> fail("Timer was rebased"));
        nearMinuteEnd();
        noTask(); // setClock alone changes neither schedule
        assertTrue(runtime.cron().reschedule(Job.class, "tick"));
        Runnable tick = next();
        atMinuteStart();
        tick.run();
        assertEquals(1, job.calls.get());
        assertTrue(far.cancel());
        CountDownLatch immediate = new CountDownLatch(1);
        runtime.timer().schedule(1, 42, Duration.ZERO, immediate::countDown);
        next().run(); // relative Timer fires even while GameTime is frozen
        assertEquals(0, immediate.getCount());
        assertTrue(errors.isEmpty());
    }

    @Test void cancellationAndRescheduleInvalidateAlreadyQueuedGenerations() throws Exception {
        nearMinuteEnd();
        Job job = new Job();
        build(job);
        Runnable obsolete = next();
        atMinuteStart();
        assertTrue(runtime.cron().reschedule(Job.class, "tick"));
        obsolete.run();
        assertEquals(0, job.calls.get());
        noTask();
        nearMinuteEnd();
        assertTrue(runtime.cron().reschedule(Job.class, "tick"));
        Runnable cancelled = next();
        assertTrue(runtime.cron().cancel(Job.class, "tick"));
        assertFalse(runtime.cron().cancel(Job.class, "tick"));
        cancelled.run();
        assertEquals(0, job.calls.get());
        noTask();
        assertTrue(runtime.cron().reschedule(Job.class, "tick"));
        Runnable restarted = next();
        atMinuteStart();
        restarted.run();
        assertEquals(1, job.calls.get());
        assertTrue(errors.isEmpty());
    }

    @Test void cancelWhileRunningStopsSubsequentCycles() throws Exception {
        nearMinuteEnd();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        class BlockingJob {
            @Cron(value = "0 * * * * ?", domain = 1, routeKey = 42)
            public void tick() {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
        build(new BlockingJob());
        Thread worker = Thread.ofPlatform().start(next());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(runtime.cron().cancel(BlockingJob.class, "tick"));
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive());
        noTask();
        assertTrue(errors.isEmpty());
    }

    @Test void rescheduleDuringExecutionCannotBeUndoneByOldCompletion() throws Exception {
        nearMinuteEnd();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        class BlockingJob {
            @Cron(value = "0 * * * * ?", domain = 1, routeKey = 42)
            public void tick() {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
            }
        }
        build(new BlockingJob());
        Thread worker = Thread.ofPlatform().start(next());
        Runnable replacement;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(runtime.cron().reschedule(BlockingJob.class, "tick"));
            replacement = next();
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive());
        noTask(); // completion of the obsolete generation must not arm another Timer
        atMinuteStart();
        replacement.run();
        assertEquals(2, calls.get());
        assertTrue(errors.isEmpty());
    }

    @Test void exceptionAndOverloadDoNotKillFutureCronCycles() throws Exception {
        nearMinuteEnd();
        AtomicInteger attempts = new AtomicInteger(), calls = new AtomicInteger();
        class BrokenJob {
            @Cron(value = "0 * * * * ?", domain = 1, routeKey = 42)
            public void tick() {
                calls.incrementAndGet();
                throw new IllegalStateException("expected cron failure");
            }
        }
        runtime = builder((d, k, task) -> {
            if (attempts.incrementAndGet() == 1) return RouteExecuteStatus.OVERLOADED;
            queue.add(task);
            return RouteExecuteStatus.ACCEPTED;
        }, new BrokenJob()).build();
        next().run(); // the next cycle after rejection is still delivered
        Runnable subsequent = next(); // the exception also preserves recurrence
        assertTrue(runtime.cron().cancel(BrokenJob.class, "tick"));
        subsequent.run();
        assertEquals(1, calls.get());
        assertEquals(List.of(ROUTE_EXECUTOR_OVERLOADED, RUNTIME_EXECUTION_ERROR),
            errors.stream().map(RuntimeError::errorCode).toList());
    }

    @Test void keysUseDeclaringClassAndRescheduleAllReactivatesCancelledEntries() throws Exception {
        Job job = new InheritedJob();
        build(job);
        assertFalse(runtime.cron().cancel(InheritedJob.class, "tick"));
        assertFalse(runtime.cron().reschedule(Job.class, "missing"));
        assertTrue(runtime.cron().cancel(Job.class, "tick"));
        nearMinuteEnd();
        runtime.cron().rescheduleAll();
        Runnable tick = next();
        atMinuteStart();
        tick.run();
        assertEquals(1, job.calls.get());
        assertThrows(NullPointerException.class, () -> runtime.cron().cancel(null, "tick"));
        assertThrows(NullPointerException.class, () -> runtime.cron().reschedule(Job.class, null));
    }

    @Test void duplicateKeysFailBuildAndCloseInvalidatesQueuedCron() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
            builder(RouteExecutors.virtualThreads(), new Job(), new InheritedJob()).build());
        nearMinuteEnd();
        Job job = new Job();
        build(job);
        Runnable queued = next();
        runtime.close();
        queued.run();
        assertEquals(0, job.calls.get());
        assertFalse(runtime.cron().cancel(Job.class, "tick"));
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class, () ->
            runtime.cron().reschedule(Job.class, "tick")).errorCode());
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class, () ->
            runtime.cron().rescheduleAll()).errorCode());
        noTask();
    }
}

