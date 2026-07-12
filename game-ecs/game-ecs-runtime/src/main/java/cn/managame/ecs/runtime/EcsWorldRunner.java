package cn.managame.ecs.runtime;

import cn.managame.ecs.SystemPipeline;
import cn.managame.ecs.World;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.runnable.GameTimerTaskRunnable;
import cn.managame.runtime.timer.Timeout;
import cn.managame.runtime.timer.TimingWheel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Drives one ECS world through game-runtime. Timer callbacks only enqueue work; the actual
 * update is routed by {@code (group, routerKey)} and therefore serialized with other scene tasks.
 */
public final class EcsWorldRunner implements AutoCloseable {

    private final World world;
    private final SystemPipeline pipeline;
    private final byte group;
    private final long routerKey;
    private final byte busType;
    private final long busId;
    private final long tickMillis;
    private final TickScheduler scheduler;
    private final TickDispatcher dispatcher;
    private final LongSupplier nanoTime;
    private final Runnable startValidator;
    private final AtomicBoolean tickPending = new AtomicBoolean();

    private volatile boolean running;
    private boolean closed;
    private volatile Timeout scheduled;
    private long lastTickNanos;

    public EcsWorldRunner(World world, SystemPipeline pipeline, byte group, long routerKey,
                          long tickMillis) {
        this(world, pipeline, group, routerKey, (byte) 0, routerKey, tickMillis,
                (delay, task) -> TimingWheel.getInstance().schedule(delay, task),
                (taskGroup, taskRouterKey, taskBusType, taskBusId, task) ->
                        ExecutorGroupRegistry.getInstance().execute(new GameTimerTaskRunnable(
                                taskGroup, taskRouterKey, taskBusType, taskBusId, null, task)),
                System::nanoTime,
                () -> {
                    if (ExecutorGroupRegistry.getInstance().get(group) == null) {
                        throw new IllegalStateException("executor group is not registered: " + group);
                    }
                });
    }

    public EcsWorldRunner(World world, SystemPipeline pipeline, byte group, long routerKey,
                          byte busType, long busId, long tickMillis) {
        this(world, pipeline, group, routerKey, busType, busId, tickMillis,
                (delay, task) -> TimingWheel.getInstance().schedule(delay, task),
                (taskGroup, taskRouterKey, taskBusType, taskBusId, task) ->
                        ExecutorGroupRegistry.getInstance().execute(new GameTimerTaskRunnable(
                                taskGroup, taskRouterKey, taskBusType, taskBusId, null, task)),
                System::nanoTime,
                () -> {
                    if (ExecutorGroupRegistry.getInstance().get(group) == null) {
                        throw new IllegalStateException("executor group is not registered: " + group);
                    }
                });
    }

    EcsWorldRunner(World world, SystemPipeline pipeline, byte group, long routerKey,
                   byte busType, long busId, long tickMillis, TickScheduler scheduler,
                   TickDispatcher dispatcher, LongSupplier nanoTime, Runnable startValidator) {
        this.world = Objects.requireNonNull(world, "world");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        if (routerKey == 0) {
            throw new IllegalArgumentException("routerKey must not be 0");
        }
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be positive: " + tickMillis);
        }
        this.group = group;
        this.routerKey = routerKey;
        this.busType = busType;
        this.busId = busId;
        this.tickMillis = tickMillis;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startValidator = Objects.requireNonNull(startValidator, "startValidator");
    }

    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("ECS world runner is already started");
        }
        if (closed) {
            throw new IllegalStateException("ECS world runner is already closed");
        }
        startValidator.run();
        running = true;
        lastTickNanos = nanoTime.getAsLong();
        try {
            scheduleNext();
        } catch (RuntimeException | Error e) {
            running = false;
            throw e;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Runs one update immediately; useful for deterministic tests and manual server loops. */
    public void tickOnce(long deltaNanos) {
        pipeline.update(world, deltaNanos);
    }

    private void onTimer() {
        if (!running) {
            return;
        }
        try {
            if (tickPending.compareAndSet(false, true)) {
                try {
                    dispatcher.dispatch(group, routerKey, busType, busId, this::runDispatchedTick);
                } catch (RuntimeException | Error e) {
                    tickPending.set(false);
                    throw e;
                }
            }
        } finally {
            if (running) {
                scheduleNext();
            }
        }
    }

    private void runDispatchedTick() {
        try {
            if (!running) {
                return;
            }
            long now = nanoTime.getAsLong();
            long delta = Math.max(0, now - lastTickNanos);
            lastTickNanos = now;
            pipeline.update(world, delta);
        } finally {
            tickPending.set(false);
        }
    }

    private void scheduleNext() {
        scheduled = scheduler.schedule(tickMillis, this::onTimer);
    }

    @Override
    public synchronized void close() {
        running = false;
        closed = true;
        Timeout current = scheduled;
        if (current != null) {
            current.cancel();
            scheduled = null;
        }
    }

    @FunctionalInterface
    interface TickScheduler {
        Timeout schedule(long delayMillis, Runnable task);
    }

    @FunctionalInterface
    interface TickDispatcher {
        void dispatch(byte group, long routerKey, byte busType, long busId, Runnable task);
    }
}
