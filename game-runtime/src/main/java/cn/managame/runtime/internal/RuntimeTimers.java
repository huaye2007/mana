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
        return schedule(domain, key, delay, task, () -> {});
    }

    // Cron uses the same timer path; a rejected cycle still advances to its next occurrence.
    TimerRef schedule(int domain, long key, Duration delay, Runnable task, Runnable onRejected) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(delay);
        validate.accept(domain, key);
        if (delay.isNegative()) throw new IllegalArgumentException("Negative delay");
        long nanos;
        try { nanos = delay.toNanos(); }
        catch (ArithmeticException tooLong) { nanos = Long.MAX_VALUE; }
        Scheduled scheduled = new Scheduled(new DefaultTimerContext(domain, key), task, onRejected);
        try {
            scheduled.timeout = wheel.newTimeout(ignored -> scheduled.fire(), nanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) {
            throw failure(RUNTIME_CLOSED);
        }
        return scheduled;
    }

    /** One scheduled action; firing and cancellation claim it exactly once. */
    private final class Scheduled extends AtomicBoolean implements TimerRef {
        private final Context context;
        private final Runnable task, onRejected;
        volatile Timeout timeout;

        Scheduled(Context context, Runnable task, Runnable onRejected) {
            this.context = context;
            this.task = task;
            this.onRejected = onRejected;
        }

        void fire() {
            if (!compareAndSet(false, true)) return;
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
    }
}
