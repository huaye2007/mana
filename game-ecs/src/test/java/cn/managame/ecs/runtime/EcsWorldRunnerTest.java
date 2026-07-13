package cn.managame.ecs.runtime;

import cn.managame.ecs.SystemPipeline;
import cn.managame.ecs.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsWorldRunnerTest {

    @Test
    void ownsItsLoopThreadAndRunsQueuedInputBeforeTheTick() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        ControlledWaiter waiter = new ControlledWaiter(clock);
        List<String> order = new ArrayList<>();
        AtomicReference<Thread> taskThread = new AtomicReference<>();
        AtomicReference<Thread> updateThread = new AtomicReference<>();
        AtomicLong delta = new AtomicLong();
        CountDownLatch updated = new CountDownLatch(1);
        SystemPipeline pipeline = new SystemPipeline().add((world, deltaNanos, commands) -> {
            order.add("update");
            updateThread.set(Thread.currentThread());
            delta.set(deltaNanos);
            updated.countDown();
        });
        EcsWorldRunner runner = new EcsWorldRunner(new World(), pipeline, "ecs-scene-99", 50, 1,
                clock::get, waiter);

        try {
            runner.start();
            assertEquals(50_001_000L, waiter.takeDeadline());
            runner.execute(() -> {
                order.add("input");
                taskThread.set(Thread.currentThread());
            });
            assertThrows(RejectedExecutionException.class, () -> runner.execute(() -> { }));
            waiter.release();

            assertTrue(updated.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("input", "update"), order);
            assertEquals(taskThread.get(), updateThread.get());
            assertEquals("ecs-scene-99", updateThread.get().getName());
            assertEquals(50_000_000L, delta.get());
        } finally {
            runner.close();
        }
        assertFalse(runner.isRunning());
        assertThrows(RejectedExecutionException.class, () -> runner.execute(() -> { }));
    }

    @Test
    void skipsMissedDeadlinesInsteadOfRunningCatchUpTicks() throws Exception {
        long period = 50_000_000L;
        AtomicLong clock = new AtomicLong(1_000);
        ControlledWaiter waiter = new ControlledWaiter(clock);
        CountDownLatch firstUpdate = new CountDownLatch(1);
        SystemPipeline pipeline = new SystemPipeline().add((world, deltaNanos, commands) -> {
            clock.addAndGet(period * 3);
            firstUpdate.countDown();
        });
        EcsWorldRunner runner = new EcsWorldRunner(new World(), pipeline, "ecs-overrun", 50,
                clock::get, waiter);

        try {
            runner.start();
            long firstDeadline = waiter.takeDeadline();
            waiter.release();
            assertTrue(firstUpdate.await(2, TimeUnit.SECONDS));

            assertEquals(firstDeadline + period * 4, waiter.takeDeadline());
        } finally {
            runner.close();
        }
    }

    @Test
    void validatesLifecycleAndManualTicks() {
        assertThrows(IllegalArgumentException.class,
                () -> new EcsWorldRunner(new World(), new SystemPipeline(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EcsWorldRunner(new World(), new SystemPipeline(), " ", 50));
        assertThrows(IllegalArgumentException.class,
                () -> new EcsWorldRunner(new World(), new SystemPipeline(), "ecs", 50, 0));

        AtomicLong updates = new AtomicLong();
        EcsWorldRunner runner = new EcsWorldRunner(new World(),
                new SystemPipeline().add((world, deltaNanos, commands) -> updates.incrementAndGet()),
                "ecs-lifecycle", 60_000);
        runner.tickOnce(123);
        assertEquals(1, updates.get());

        runner.start();
        assertThrows(IllegalStateException.class, runner::start);
        assertThrows(IllegalStateException.class, () -> runner.tickOnce(123));
        runner.close();
        assertThrows(IllegalStateException.class, runner::start);
        assertThrows(IllegalStateException.class, () -> runner.tickOnce(123));
    }

    private static final class ControlledWaiter implements EcsWorldRunner.TickWaiter {
        private final AtomicLong clock;
        private final BlockingQueue<Long> deadlines = new LinkedBlockingQueue<>();
        private final Semaphore releases = new Semaphore(0);

        private ControlledWaiter(AtomicLong clock) {
            this.clock = clock;
        }

        @Override
        public void await(long deadlineNanos) throws InterruptedException {
            deadlines.add(deadlineNanos);
            releases.acquire();
            clock.set(deadlineNanos);
        }

        private long takeDeadline() throws InterruptedException {
            Long deadline = deadlines.poll(2, TimeUnit.SECONDS);
            if (deadline == null) {
                throw new AssertionError("runner did not wait for the next tick");
            }
            return deadline;
        }

        private void release() {
            releases.release();
        }
    }
}
