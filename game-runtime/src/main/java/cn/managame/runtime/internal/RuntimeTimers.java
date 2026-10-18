package cn.managame.runtime.internal;

import static cn.managame.core.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.DefaultTimerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.timer.RuntimeTimer;
import cn.managame.runtime.timer.TimerRef;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Relative, monotonic delay scheduling on a hashed timing wheel: O(1) schedule and cancel, so large numbers
 * of game timers (buffs, cooldowns, expiries) stay cheap. A due timer only submits its action to the Route;
 * business code never runs on the wheel thread. Resolution is one {@link #TICK_MILLIS} tick: a timer fires
 * no earlier than its delay and normally within one tick after it.
 */
final class RuntimeTimers implements RuntimeTimer, AutoCloseable {
    static final long TICK_MILLIS = 10;
    private static final int WHEEL_SIZE = 1024;
    private final HashedWheelTimer wheel = new HashedWheelTimer(
            Thread.ofPlatform().daemon().name("managame-runtime-timer-", 0).factory(),
            TICK_MILLIS, TimeUnit.MILLISECONDS, WHEEL_SIZE);
    private static final Runnable NOTHING = () -> {};
    /**
     * Timers still pending per Route, so one Route's work can be cancelled together. Costs one map entry
     * per Route that has timers and one set entry per timer; both are dropped as each timer fires or is
     * cancelled, so Routes that are quiet hold nothing.
     */
    private final ConcurrentHashMap<Route, Set<Scheduled>> pending = new ConcurrentHashMap<>();
    private final BiConsumer<Integer, Long> validate;
    private final BiFunction<Context, Runnable, Integer> submit;
    private final RuntimeErrorHandler errors;

    RuntimeTimers(BiConsumer<Integer, Long> validate,
                  BiFunction<Context, Runnable, Integer> submit, RuntimeErrorHandler errors) {
        this.validate = validate;
        this.submit = submit;
        this.errors = errors;
    }

    public TimerRef schedule(int domain, long key, Duration delay, Runnable task) {
        return schedule(domain, key, delay, task, NOTHING, true);
    }

    // Cron uses the same timer path; a rejected cycle still advances to its next occurrence.
    TimerRef schedule(int domain, long key, Duration delay, Runnable task, Runnable onRejected) {
        // Untracked: cancelAll must not silently stop a Cron, whose next cycle is registered only after
        // the current one runs. Cron schedules are cancelled through CronScheduler.
        return schedule(domain, key, delay, task, onRejected, false);
    }

    private TimerRef schedule(int domain, long key, Duration delay, Runnable task, Runnable onRejected, boolean track) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(delay);
        validate.accept(domain, key);
        if (delay.isNegative()) throw new IllegalArgumentException("Negative delay");
        long nanos;
        try { nanos = delay.toNanos(); }
        catch (ArithmeticException tooLong) { nanos = Long.MAX_VALUE; }
        Scheduled scheduled = new Scheduled(new DefaultTimerContext(domain, key), task, onRejected,
                track ? new Route(domain, key) : null);
        // Index before arming the wheel: a timer that fires first must find itself there to remove itself.
        scheduled.remember();
        try {
            scheduled.timeout = wheel.newTimeout(ignored -> scheduled.fire(), nanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) {
            scheduled.forget();
            throw failure(RUNTIME_CLOSED);
        }
        return scheduled;
    }

    public int cancelAll(int domain, long key) {
        Set<Scheduled> route = pending.remove(new Route(domain, key));
        if (route == null) return 0;
        int cancelled = 0;
        for (Scheduled scheduled : route) if (scheduled.cancel()) cancelled++;
        return cancelled;
    }

    /** Complete Route identity, as everywhere else: the Key alone does not isolate anything. */
    private record Route(int domain, long key) {}

    /** One scheduled action; firing and cancellation claim it exactly once. */
    private final class Scheduled extends AtomicBoolean implements TimerRef {
        private final Context context;
        private final Runnable task, onRejected;
        /** The Route this timer is indexed under, or null when it is not tracked (Cron). */
        private final Route route;
        volatile Timeout timeout;

        Scheduled(Context context, Runnable task, Runnable onRejected, Route route) {
            this.context = context;
            this.task = task;
            this.onRejected = onRejected;
            this.route = route;
        }

        void remember() {
            if (route != null) pending.computeIfAbsent(route, ignored -> ConcurrentHashMap.newKeySet()).add(this);
        }

        /** Drops the index entry, and the Route's empty set with it, so idle Routes cost nothing. */
        void forget() {
            if (route == null) return;
            pending.computeIfPresent(route, (ignored, scheduled) -> {
                scheduled.remove(this);
                return scheduled.isEmpty() ? null : scheduled;
            });
        }

        void fire() {
            if (!compareAndSet(false, true)) return;
            forget();
            int error = submit.apply(context, () -> {
                try { task.run(); }
                catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
            });
            if (error != 0 && error != RUNTIME_CLOSED) {
                report(error, context, failure(error));
                onRejected.run();
            }
        }

        @Override public boolean cancel() {
            if (!compareAndSet(false, true)) return false;
            forget();
            Timeout current = timeout;
            if (current != null) current.cancel();
            return true;
        }
    }

    private void report(int code, Context context, Throwable cause) {
        errors.onError(new RuntimeError(code, context, context.routeDomain(), context.routeKey(), cause));
    }

    private static RuntimeDispatchException failure(int code) {
        return new RuntimeDispatchException(code, "Runtime timer rejected: " + code);
    }

    /** Stops the wheel; pending timers never fire. Does not wait for a submission in progress. */
    public void close() {
        wheel.stop();
        pending.clear();
    }
}
