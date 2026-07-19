package cn.managame.runtime.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-threaded hashed timing wheel. Callbacks run on the wheel thread and must only perform
 * lightweight dispatch; game work belongs on an executor group.
 */
public final class TimingWheel {

    private static final Logger logger = LoggerFactory.getLogger(TimingWheel.class);
    private static final long DEFAULT_TICK_MS = 100;
    private static final int DEFAULT_WHEEL_SIZE = 512;
    private static final TimingWheel INSTANCE =
            new TimingWheel("timer-wheel", DEFAULT_TICK_MS, DEFAULT_WHEEL_SIZE);

    public static TimingWheel getInstance() {
        return INSTANCE;
    }

    private final long tickMs;
    private final long startTimeMs;
    private final int mask;
    private final List<TimeoutTask>[] buckets;
    private final ConcurrentLinkedQueue<TimeoutTask> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong pendingCount = new AtomicLong();
    private final Thread worker;
    private final Object lifecycleLock = new Object();
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    public TimingWheel(String name, long tickMs, int wheelSize) {
        if (tickMs <= 0 || tickMs > Long.MAX_VALUE / 4 / 1_000_000L) {
            throw new IllegalArgumentException("invalid tickMs: " + tickMs);
        }
        int normalized = normalizeToPowerOfTwo(wheelSize);
        this.tickMs = tickMs;
        this.startTimeMs = GameTime.currentTimeMillis();
        this.mask = normalized - 1;
        this.buckets = new List[normalized];
        for (int i = 0; i < normalized; i++) {
            buckets[i] = new LinkedList<>();
        }
        this.worker = new Thread(this::workerLoop, Objects.requireNonNull(name, "name"));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private static int normalizeToPowerOfTwo(int wheelSize) {
        if (wheelSize <= 0 || wheelSize > (1 << 30)) {
            throw new IllegalArgumentException("wheelSize must be in [1, 2^30]: " + wheelSize);
        }
        int n = Integer.highestOneBit(wheelSize);
        return n == wheelSize ? n : n << 1;
    }

    /**
     * Schedules one callback. The callback never fires before its monotonic deadline and may be
     * delayed by at most one tick under normal load.
     */
    public Timeout schedule(long delayMs, Runnable task) {
        Objects.requireNonNull(task, "task");
        long normalizedDelayMs = Math.max(0, delayMs);
        if (normalizedDelayMs > Long.MAX_VALUE / 4 / 1_000_000L) {
            throw new IllegalArgumentException("delayMs is too large: " + delayMs);
        }
        long deadlineMs = addWithSaturation(GameTime.currentTimeMillis(), normalizedDelayMs);
        synchronized (lifecycleLock) {
            if (!running) {
                throw new IllegalStateException("timing wheel already shut down");
            }
            TimeoutTask timeout = new TimeoutTask(this, task, deadlineMs);
            pendingCount.incrementAndGet();
            pending.add(timeout);
            return timeout;
        }
    }

    public long pendingCount() {
        return pendingCount.get();
    }

    public void shutdown() {
        shutdown(5_000);
    }

    /**
     * Stops accepting timers, cancels outstanding handles and waits up to {@code timeoutMs} for
     * the wheel thread. Returns whether the worker has stopped.
     */
    public boolean shutdown(long timeoutMs) {
        synchronized (lifecycleLock) {
            running = false;
        }
        worker.interrupt();
        if (Thread.currentThread() != worker && timeoutMs > 0) {
            try {
                worker.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return !worker.isAlive();
    }

    private void workerLoop() {
        long tick = 0;
        try {
            while (running) {
                long tickDeadlineMs = addWithSaturation(startTimeMs,
                        multiplyWithSaturation(tick + 1, tickMs));
                long sleepMs = tickDeadlineMs - GameTime.currentTimeMillis();
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(Math.min(sleepMs, tickMs));
                    } catch (InterruptedException e) {
                        if (!running) {
                            return;
                        }
                    }
                    continue;
                }
                transferPending(tick);
                expireBucket(buckets[(int) (tick & mask)]);
                tick++;
            }
        } finally {
            cancelOutstanding();
        }
    }

    private void transferPending(long tick) {
        TimeoutTask timeout;
        while ((timeout = pending.poll()) != null) {
            if (timeout.isCancelled()) {
                continue;
            }
            long millisFromStart = timeout.deadlineMs - startTimeMs;
            long deadlineTick = millisFromStart <= 0
                    ? tick
                    : (millisFromStart - 1) / tickMs;
            long targetTick = Math.max(deadlineTick, tick);
            timeout.remainingRounds = (targetTick - tick) / buckets.length;
            buckets[(int) (targetTick & mask)].add(timeout);
        }
    }

    private void expireBucket(List<TimeoutTask> bucket) {
        Iterator<TimeoutTask> iterator = bucket.iterator();
        while (iterator.hasNext()) {
            TimeoutTask timeout = iterator.next();
            if (timeout.isCancelled()) {
                iterator.remove();
            } else if (timeout.remainingRounds <= 0) {
                iterator.remove();
                timeout.expire();
            } else {
                timeout.remainingRounds--;
            }
        }
    }

    private void cancelOutstanding() {
        TimeoutTask timeout;
        while ((timeout = pending.poll()) != null) {
            timeout.cancel();
        }
        for (List<TimeoutTask> bucket : buckets) {
            for (TimeoutTask item : bucket) {
                item.cancel();
            }
            bucket.clear();
        }
    }

    private static long addWithSaturation(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return result;
    }

    private static long multiplyWithSaturation(long left, long right) {
        if (left == 0 || right == 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static final class TimeoutTask implements Timeout {

        private static final int WAITING = 0;
        private static final int CANCELLED = 1;
        private static final int EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<TimeoutTask> STATE =
                AtomicIntegerFieldUpdater.newUpdater(TimeoutTask.class, "state");

        private final TimingWheel owner;
        private final Runnable task;
        private final long deadlineMs;
        private long remainingRounds;
        private volatile int state = WAITING;

        private TimeoutTask(TimingWheel owner, Runnable task, long deadlineMs) {
            this.owner = owner;
            this.task = task;
            this.deadlineMs = deadlineMs;
        }

        @Override
        public boolean cancel() {
            if (!STATE.compareAndSet(this, WAITING, CANCELLED)) {
                return false;
            }
            owner.pendingCount.decrementAndGet();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return state == CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state == EXPIRED;
        }

        private void expire() {
            if (!STATE.compareAndSet(this, WAITING, EXPIRED)) {
                return;
            }
            owner.pendingCount.decrementAndGet();
            try {
                task.run();
            } catch (Exception e) {
                logger.error("timing wheel task failed", e);
            }
        }
    }
}
