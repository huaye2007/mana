package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.CallbackFailureException;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.route.Route;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.*;

/** One-shot response adapter. Capacity rejection is retryable; closed/broken runtimes fail completion. */
public final class RuntimeCallback<T> {
    private final GameRuntime runtime;
    private final HandlerContext context;
    private final Consumer<? super T> success;
    private final Consumer<? super Throwable> failure;
    private final AtomicBoolean signalled = new AtomicBoolean();
    private final RouteTask completion;

    RuntimeCallback(GameRuntime runtime, HandlerContext context, Consumer<? super T> success, Consumer<? super Throwable> failure) {
        this.runtime = runtime; this.context = context; this.success = Objects.requireNonNull(success);
        this.failure = failure;
        this.completion = runtime.task(context.route());
    }

    public Route route() { return context.route(); }
    /** A signal has claimed this callback; only retryable rejection resets it. Also true after terminal failure. */
    public boolean isSignalled() { return signalled.get(); }
    /** @deprecated This reports signal state, not business completion. Use isSignalled() or completion().isDone(). */
    @Deprecated
    public boolean isCompleted() { return isSignalled(); }
    /**
     * Stable handle for callback business completion, including business or backend failures.
     * Remains pending until a signal executes or fails permanently. Capacity rejection remains retryable.
     * Runtime does not track unsignalled remote operations; adapters must eventually signal success/failure.
     */
    public RouteTask completion() { return completion; }

    public boolean onSuccess(T responseBody) {
        if (!signalled.compareAndSet(false, true)) return false;
        enqueue(() -> success.accept(responseBody));
        return true;
    }

    /** Compatibility helper; new integrations should pass their original exception. */
    @Deprecated
    public boolean onFail(int code) {
        if (code <= 0) throw new IllegalArgumentException("Failure code must be positive");
        return onFail(new CallbackFailureException(code));
    }

    public boolean onFail(Throwable error) {
        Objects.requireNonNull(error);
        if (!signalled.compareAndSet(false, true)) return false;
        enqueue(() -> {
            if (failure != null) failure.accept(error);
            else throw new HandlerException("callback", context, error);
        });
        return true;
    }

    /**
     * Abandon an unsignalled or retryably rejected callback without executing business code.
     * Returns false if a signal/abort already owns it. Accepted signals cannot be cancelled.
     * Completion notifications still follow normal route admission; this method does not report
     * the error again (the rejected submission was already reported).
     */
    public boolean abort(Throwable error) {
        Objects.requireNonNull(error);
        if (!signalled.compareAndSet(false, true)) return false;
        completion.fail(error);
        return true;
    }

    private void enqueue(Runnable action) {
        try { runtime.dispatchCallback(context, action, completion); }
        catch (RuntimeException | Error error) {
            HandlerException failure = new HandlerException("callback dispatch", context, error);
            if (error instanceof RejectedExecutionException && !(error instanceof RuntimeClosedException))
                signalled.set(false);
            else completion.fail(failure);
            runtime.report("callback dispatch", context, failure);
            throw failure;
        }
    }
}
