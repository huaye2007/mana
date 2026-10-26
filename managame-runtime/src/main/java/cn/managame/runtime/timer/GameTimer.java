package cn.managame.runtime.timer;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.GameTask;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs tasks later or periodically on a route, through the runtime:
 *
 * <pre>{@code
 * timer.after(Duration.ofSeconds(30), context -> buffs.expire(context));      // the current task's route
 * timer.every(GameContext.of(WORLD, 0), Duration.ofMinutes(1), context -> world.tick());
 * }</pre>
 *
 * Timers live on a hashed timing wheel, so adding and cancelling one costs the same however many there are,
 * which suits a timer per player. A timer fires within one tick after it is due; the tick is 10 ms by default.
 * The wheel's single thread only hands due tasks to the runtime, so business logic never runs on it and
 * ordering with the route's other tasks holds. Delays use the monotonic clock, unaffected by changes to
 * GameTime. A periodic timer keeps a fixed rate; a firing the runtime refuses, for example because the route is
 * full, is logged and skipped, and once the runtime is shut down the timer stops.
 */
public final class GameTimer implements AutoCloseable {
    public static final Duration DEFAULT_TICK = Duration.ofMillis(10);
    private static final int WHEEL_SIZE = 512;
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime.timer");
    private final GameRuntime runtime;
    private final HashedWheelTimer wheel;

    public GameTimer(GameRuntime runtime) { this(runtime, DEFAULT_TICK); }

    /** {@code tick}, at least one millisecond, is the precision: a timer fires within one tick after it is due. */
    public GameTimer(GameRuntime runtime, Duration tick) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (tick.toMillis() < 1) throw new IllegalArgumentException("tick must be at least one millisecond");
        wheel = new HashedWheelTimer(Thread.ofPlatform().name("game-timer").daemon().factory(),
                tick.toNanos(), TimeUnit.NANOSECONDS, WHEEL_SIZE, false, -1);
    }

    /**
     * Runs {@code task} once after {@code delay} on the route of the task calling this, with a plain copy of its
     * context: same route and metadata, but a request is not answered from the timer. Must be called from a game
     * task.
     */
    public Scheduled after(Duration delay, GameTask<? super GameContext> task) {
        return after(GameContext.copyOf(Context.current()), delay, task);
    }

    /** Runs {@code task} once after {@code delay} with {@code context}. */
    public <C extends Context> Scheduled after(C context, Duration delay, GameTask<? super C> task) {
        Firing<C> firing = new Firing<>(context, task, 0);
        firing.arm(nanos(delay));
        return firing;
    }

    /** Runs {@code task} every {@code period}, first after one period, on the calling task's route; see {@link #after(Duration, GameTask)}. */
    public Scheduled every(Duration period, GameTask<? super GameContext> task) {
        return every(GameContext.copyOf(Context.current()), period, task);
    }

    /** Runs {@code task} every {@code period}, first after one period, with {@code context}. */
    public <C extends Context> Scheduled every(C context, Duration period, GameTask<? super C> task) {
        long nanos = nanos(period);
        if (nanos <= 0) throw new IllegalArgumentException("period must be positive");
        Firing<C> firing = new Firing<>(context, task, nanos);
        firing.nextDue = System.nanoTime() + nanos;
        firing.arm(nanos);
        return firing;
    }

    /** Cancels every timer; firings already handed to the runtime still run. Timers cannot be added afterwards. */
    @Override public void close() {
        for (Timeout pending : wheel.stop()) ((Firing<?>) pending.task()).state.set(Firing.CANCELLED);
    }

    private static long nanos(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Duration must not be negative: " + duration);
        return duration.toNanos();
    }

    private final class Firing<C extends Context> implements TimerTask, Scheduled {
        static final int ACTIVE = 0, FIRED = 1, CANCELLED = 2;
        final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final C context;
        private final GameTask<? super C> task;
        /** Zero for a one-shot timer. */
        private final long periodNanos;
        /** When the next periodic firing is due on the monotonic clock; used by the wheel's thread only. */
        long nextDue;
        private volatile Timeout timeout;

        Firing(C context, GameTask<? super C> task, long periodNanos) {
            this.context = Objects.requireNonNull(context, "context");
            this.task = Objects.requireNonNull(task, "task");
            this.periodNanos = periodNanos;
        }

        void arm(long delayNanos) {
            timeout = wheel.newTimeout(this, delayNanos, TimeUnit.NANOSECONDS);
            // A cancel racing with re-arming may have missed the new timeout.
            if (state.get() == CANCELLED) timeout.cancel();
        }

        @Override public void run(Timeout expired) {
            if (periodNanos == 0 ? !state.compareAndSet(ACTIVE, FIRED) : state.get() != ACTIVE) return;
            try {
                runtime.execute(context, task);
            } catch (GameRuntimeException refused) {
                if (refused.errorCode() == RUNTIME_CLOSED) {
                    cancel();
                    return;
                }
                LOG.log(Level.WARNING, "Timer firing refused; domain=" + context.domain()
                        + ", routingKey=" + context.routingKey() + (periodNanos > 0 ? "; skipped once" : ""), refused);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Timer firing failed; domain=" + context.domain(), e);
            }
            if (periodNanos == 0 || state.get() != ACTIVE) return;
            nextDue += periodNanos; // a fixed rate: late firings do not shift later ones
            try {
                arm(Math.max(0, nextDue - System.nanoTime()));
            } catch (IllegalStateException stopped) { // the timer was closed meanwhile
                state.set(CANCELLED);
            }
        }

        @Override public boolean cancel() {
            if (!state.compareAndSet(ACTIVE, CANCELLED)) return false;
            Timeout current = timeout;
            if (current != null) current.cancel();
            return true;
        }

        @Override public boolean isDone() { return state.get() != ACTIVE; }
    }
}
