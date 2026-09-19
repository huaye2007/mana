package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Completion handle. Platform handlers and same-domain virtual handlers cannot block on pending tasks. */
public final class RouteTask implements Future<Void> {
    private final GameRuntime owner;
    private final Route route;
    private final Object domain;
    private final Object completionLock = new Object();
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean done;
    private Throwable failure;
    private List<Consumer<Throwable>> observers;

    RouteTask(GameRuntime owner, Route route, Object domain) {
        this.owner = owner; this.route = route; this.domain = domain;
    }

    void succeed() { finish(null); }
    void fail(Throwable error) { finish(Objects.requireNonNull(error)); }

    private void finish(Throwable error) {
        List<Consumer<Throwable>> ready;
        synchronized (completionLock) {
            if (done) return;
            failure = error;
            ready = observers;
            observers = null;
            done = true;
            completed.countDown();
        }
        if (ready != null) for (var observer : ready) notifyObserver(observer, error);
    }

    /**
     * Notify the current caller's Route after this task finishes, using callback admission.
     * Captures the caller's metadata now. Never runs the listener inline.
     * The listener receives null on success or the task failure otherwise.
     * The returned task completes when the listener finishes, or fails if notification is rejected.
     */
    public RouteTask onComplete(Consumer<? super Throwable> listener) {
        if (!HandlerContexts.belongsTo(owner))
            throw new IllegalStateException("Completion notification requires a current handler in this runtime or an explicit target Route");
        return onComplete(HandlerContexts.current().route(), listener);
    }

    /** Explicit notification destination, also usable outside a handler. */
    public RouteTask onComplete(Route target, Consumer<? super Throwable> listener) {
        Objects.requireNonNull(listener);
        HandlerContext context = owner.callbackContext(Objects.requireNonNull(target));
        RouteTask notification = owner.task(target);
        observeCompletion(failure -> CompletionNotifications.deliver(() -> {
            try {
                owner.dispatchCallback(context, () -> listener.accept(failure), notification);
            } catch (RuntimeException | Error error) {
                HandlerException rejected = new HandlerException("task completion dispatch", context, error);
                notification.fail(rejected);
                owner.report("task completion dispatch", context, rejected);
            }
        }));
        return notification;
    }

    /**
     * Observe terminal success (null) or failure for infrastructure cleanup and failure notification.
     * Pending observers run on the completing thread; late observers run on the registering thread.
     * Observers run outside the state lock, must return promptly, and have no Route/context guarantee.
     * Observer exceptions are isolated from the task and other observers. Use onComplete for business.
     */
    public void observeCompletion(Consumer<Throwable> observer) {
        Objects.requireNonNull(observer);
        Throwable result;
        synchronized (completionLock) {
            if (!done) {
                if (observers == null) observers = new ArrayList<>();
                observers.add(observer);
                return;
            }
            result = failure;
        }
        notifyObserver(observer, result);
    }

    private static void notifyObserver(Consumer<Throwable> observer, Throwable failure) {
        try { observer.accept(failure); }
        catch (Throwable ignored) {
            // Observation cannot alter completion, strand other observers, or interrupt route cleanup.
        }
    }

    private void checkWait() {
        if (isDone()) return;
        HandlerContext current = HandlerContexts.currentOrNull();
        if (current == null) return;
        if (!Thread.currentThread().isVirtual())
            throw new IllegalStateException("Platform-thread handlers cannot wait for unfinished RouteTask: " + route);
        if (HandlerContexts.belongsTo(owner) && (current.route().equals(route) || HandlerContexts.currentDomain() == domain))
            throw new IllegalStateException("Cannot wait for unfinished work in the current route execution domain: " + route);
    }

    /** Wait without consuming interruption; failures are reported as unchecked completion errors. */
    public void join() {
        checkWait();
        boolean interrupted = false;
        try {
            while (!done) {
                try { completed.await(); }
                catch (InterruptedException error) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure instanceof CancellationException cancelled) throw cancelled;
        if (failure instanceof CompletionException completion) throw completion;
        if (failure != null) throw new CompletionException(failure);
    }

    public Void get() throws InterruptedException, ExecutionException {
        checkWait();
        if (!done) completed.await();
        return result();
    }

    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        checkWait();
        if (!done && !completed.await(timeout, unit)) throw new TimeoutException("Route task has not completed");
        return result();
    }

    private Void result() throws ExecutionException {
        if (failure instanceof CancellationException cancelled) throw cancelled;
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            throw new ExecutionException(cause);
        }
        return null;
    }

    /** Accepted route tasks are not cancellable through this handle; timers may cancel before dispatch. */
    public boolean cancel(boolean interrupt) { return false; }
    public boolean isCancelled() { return done && failure instanceof CancellationException; }
    public boolean isDone() { return done; }
}
