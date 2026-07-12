package cn.managame.ecs.runtime;

import cn.managame.ecs.SystemPipeline;
import cn.managame.ecs.World;
import cn.managame.runtime.timer.Timeout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsWorldRunnerTest {

    @Test
    void schedulesRoutedTicksWithoutBuildingABacklog() {
        List<FakeTimeout> timers = new ArrayList<>();
        List<Runnable> dispatched = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger updates = new AtomicInteger();
        AtomicLong delta = new AtomicLong();
        SystemPipeline pipeline = new SystemPipeline().add((world, deltaNanos, commands) -> {
            updates.incrementAndGet();
            delta.set(deltaNanos);
        });
        EcsWorldRunner runner = new EcsWorldRunner(new World(), pipeline, (byte) 3, 99,
                (byte) 7, 8, 50,
                (delay, task) -> {
                    FakeTimeout timeout = new FakeTimeout(delay, task);
                    timers.add(timeout);
                    return timeout;
                },
                (group, routerKey, busType, busId, task) -> dispatched.add(task),
                clock::get, () -> { });

        runner.start();
        assertEquals(50, timers.getFirst().delayMillis);
        timers.getFirst().expire();
        timers.get(1).expire();
        assertEquals(1, dispatched.size(), "a pending tick suppresses another dispatch");

        clock.set(21_000);
        dispatched.getFirst().run();
        assertEquals(1, updates.get());
        assertEquals(20_000, delta.get());

        timers.get(2).expire();
        assertEquals(2, dispatched.size());
        runner.close();
        assertFalse(runner.isRunning());
        assertTrue(timers.getLast().isCancelled());
    }

    @Test
    void validatesConfigurationAndStartState() {
        assertThrows(IllegalArgumentException.class,
                () -> new EcsWorldRunner(new World(), new SystemPipeline(), (byte) 3, 0, 50));
        EcsWorldRunner runner = new EcsWorldRunner(new World(), new SystemPipeline(), (byte) 3, 1,
                (byte) 0, 1, 50, (delay, task) -> new FakeTimeout(delay, task),
                (group, key, type, id, task) -> { }, System::nanoTime, () -> { });
        runner.start();
        assertThrows(IllegalStateException.class, runner::start);
        runner.close();
        assertThrows(IllegalStateException.class, runner::start);
    }

    private static final class FakeTimeout implements Timeout {
        private final long delayMillis;
        private final Runnable task;
        private boolean cancelled;
        private boolean expired;

        private FakeTimeout(long delayMillis, Runnable task) {
            this.delayMillis = delayMillis;
            this.task = task;
        }

        void expire() {
            expired = true;
            task.run();
        }

        @Override
        public boolean cancel() {
            if (cancelled || expired) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isExpired() {
            return expired;
        }
    }
}
