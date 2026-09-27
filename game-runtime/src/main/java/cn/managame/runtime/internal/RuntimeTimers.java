package cn.managame.runtime.internal;

import cn.managame.runtime.timer.Cron;

import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.DefaultTimerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.timer.RuntimeTimer;
import cn.managame.runtime.timer.TimerRef;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import static cn.managame.core.FrameworkErrorCodes.*;

/** Relative, monotonic delay scheduling; business actions always enter a Route. */
final class RuntimeTimers implements RuntimeTimer, AutoCloseable {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("managame-runtime-timer-", 0).factory());
    private final BiConsumer<Integer, Long> validate;
    private final BiFunction<Context, Runnable, Integer> submit;
    private final RuntimeErrorHandler errors;

    RuntimeTimers(BiConsumer<Integer, Long> validate,
                  BiFunction<Context, Runnable, Integer> submit, RuntimeErrorHandler errors) {
        this.validate = validate;
        this.submit = submit;
        this.errors = errors;
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
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
        AtomicBoolean claimed = new AtomicBoolean();
        Context context = new DefaultTimerContext(domain, key);
        ScheduledFuture<?> future;
        try {
            future = scheduler.schedule(() -> {
                if (!claimed.compareAndSet(false, true)) return;
                int error = submit.apply(context, () -> {
                    try { task.run(); }
                    catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
                });
                if (error != 0 && error != RUNTIME_CLOSED) {
                    report(error, context, failure(error));
                    onRejected.run();
                }
            }, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            throw failure(RUNTIME_CLOSED);
        }
        return () -> {
            if (!claimed.compareAndSet(false, true)) return false;
            future.cancel(false);
            return true;
        };
    }

    private void report(int code, Context context, Throwable cause) {
        errors.onError(new RuntimeError(code, context, context.routeDomain(), context.routeKey(), cause));
    }

    private static RuntimeDispatchException failure(int code) {
        return new RuntimeDispatchException(code, "Runtime timer rejected: " + code);
    }

    public void close() {
        scheduler.shutdownNow();
    }
}
