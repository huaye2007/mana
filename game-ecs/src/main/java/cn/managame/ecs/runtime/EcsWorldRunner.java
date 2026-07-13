package cn.managame.ecs.runtime;

import cn.managame.ecs.SystemPipeline;
import cn.managame.ecs.World;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Owns the update loop and thread for one ECS world.
 *
 * <p>The world is thread-confined to the loop thread after {@link #start()}. Cross-thread input
 * must be submitted with {@link #execute(Runnable)} and is drained before the next world update.
 * The loop uses a monotonic clock and skips missed frame deadlines instead of running catch-up
 * ticks back-to-back.</p>
 */
public final class EcsWorldRunner implements AutoCloseable {

    private static final AtomicLong RUNNER_IDS = new AtomicLong();
    private static final int DEFAULT_PENDING_TASK_CAPACITY = 16_384;

    private final World world;
    private final SystemPipeline pipeline;
    private final long tickNanos;
    private final String threadName;
    private final LongSupplier nanoTime;
    private final TickWaiter tickWaiter;
    private final BlockingQueue<Runnable> pendingTasks;

    private volatile boolean running;
    private boolean started;
    private boolean closed;
    private volatile Thread loopThread;

    public EcsWorldRunner(World world, SystemPipeline pipeline, long tickMillis) {
        this(world, pipeline, "ecs-world-" + RUNNER_IDS.incrementAndGet(), tickMillis);
    }

    public EcsWorldRunner(World world, SystemPipeline pipeline, String threadName, long tickMillis) {
        this(world, pipeline, threadName, tickMillis, DEFAULT_PENDING_TASK_CAPACITY);
    }

    public EcsWorldRunner(World world, SystemPipeline pipeline, String threadName, long tickMillis,
                          int pendingTaskCapacity) {
        this(world, pipeline, threadName, tickMillis, pendingTaskCapacity, System::nanoTime, null);
    }

    EcsWorldRunner(World world, SystemPipeline pipeline, String threadName, long tickMillis,
                   LongSupplier nanoTime, TickWaiter tickWaiter) {
        this(world, pipeline, threadName, tickMillis, DEFAULT_PENDING_TASK_CAPACITY,
                nanoTime, tickWaiter);
    }

    EcsWorldRunner(World world, SystemPipeline pipeline, String threadName, long tickMillis,
                   int pendingTaskCapacity, LongSupplier nanoTime, TickWaiter tickWaiter) {
        this.world = Objects.requireNonNull(world, "world");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.threadName = requireThreadName(threadName);
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be positive: " + tickMillis);
        }
        try {
            this.tickNanos = Math.multiplyExact(tickMillis, 1_000_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("tickMillis is too large: " + tickMillis, e);
        }
        if (pendingTaskCapacity <= 0) {
            throw new IllegalArgumentException(
                    "pendingTaskCapacity must be positive: " + pendingTaskCapacity);
        }
        this.pendingTasks = new ArrayBlockingQueue<>(pendingTaskCapacity);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.tickWaiter = tickWaiter == null ? this::parkUntil : tickWaiter;
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("ECS world runner is already closed");
        }
        if (started) {
            throw new IllegalStateException("ECS world runner is already started");
        }
        started = true;
        running = true;
        Thread thread = Thread.ofPlatform()
                .name(threadName)
                .unstarted(this::runLoop);
        loopThread = thread;
        try {
            thread.start();
        } catch (RuntimeException | Error e) {
            loopThread = null;
            running = false;
            started = false;
            throw e;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Enqueues input to run on the world loop before its next update.
     *
     * @throws RejectedExecutionException if the runner is not accepting work
     */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        Thread thread;
        synchronized (this) {
            if (!running || closed) {
                throw new RejectedExecutionException("ECS world runner is not running");
            }
            if (!pendingTasks.offer(task)) {
                throw new RejectedExecutionException("ECS world runner input queue is full");
            }
            thread = loopThread;
        }
        LockSupport.unpark(thread);
    }

    /** Runs one update for a world that has not been started on its own loop. */
    public synchronized void tickOnce(long deltaNanos) {
        if (closed) {
            throw new IllegalStateException("ECS world runner is already closed");
        }
        if (started) {
            throw new IllegalStateException("cannot tick manually after the ECS loop has started");
        }
        pipeline.update(world, deltaNanos);
    }

    private void runLoop() {
        long lastTick = nanoTime.getAsLong();
        long nextTick = addClamped(lastTick, tickNanos);
        try {
            while (running) {
                tickWaiter.await(nextTick);
                if (!running) {
                    break;
                }

                long now = nanoTime.getAsLong();
                long delta = Math.max(0, now - lastTick);
                lastTick = now;

                drainPendingTasks();
                if (!running) {
                    break;
                }
                pipeline.update(world, delta);

                long afterUpdate = nanoTime.getAsLong();
                nextTick = addClamped(nextTick, tickNanos);
                if (nextTick <= afterUpdate) {
                    nextTick = addClamped(afterUpdate, tickNanos);
                }
            }
        } catch (InterruptedException e) {
            if (running) {
                Thread.currentThread().interrupt();
            }
        } finally {
            synchronized (this) {
                running = false;
                loopThread = null;
                pendingTasks.clear();
            }
        }
    }

    private void drainPendingTasks() {
        int available = pendingTasks.size();
        for (int i = 0; i < available; i++) {
            Runnable task = pendingTasks.poll();
            if (task == null) {
                return;
            }
            task.run();
            if (!running) {
                return;
            }
        }
    }

    private void parkUntil(long deadlineNanos) throws InterruptedException {
        while (running) {
            long remaining = deadlineNanos - nanoTime.getAsLong();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(this, remaining);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            if (!closed) {
                closed = true;
                running = false;
            }
            thread = loopThread;
        }

        if (thread == null) {
            return;
        }
        thread.interrupt();
        LockSupport.unpark(thread);
        if (thread == Thread.currentThread()) {
            return;
        }

        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
                thread.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String requireThreadName(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        return threadName;
    }

    private static long addClamped(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    @FunctionalInterface
    interface TickWaiter {
        void await(long deadlineNanos) throws InterruptedException;
    }
}
