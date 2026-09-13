package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Completion handle. Platform handlers and same-domain virtual handlers cannot block on pending tasks. */
public final class RouteTask implements Future<Void> {
    private final GameRuntime owner;
    private final Route route;
    private final Object domain;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    RouteTask(GameRuntime owner, Route route, Object domain) {
        this.owner = owner; this.route = route; this.domain = domain;
    }

    void succeed() { completion.complete(null); }
    void fail(Throwable error) { completion.completeExceptionally(error); }

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
        completion.whenComplete((ignored, failure) -> CompletionNotifications.deliver(() -> {
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
     * Read-only completion observation for infrastructure bridges, including backend failure.
     * Listeners follow CompletionStage threading rules; they have no route/context guarantee and
     * must not execute routed business logic. Use onComplete for business notifications.
     * Completing a future obtained from this stage cannot complete or cancel the route task.
     */
    public CompletionStage<Void> completionStage() { return completion.minimalCompletionStage(); }

    private void checkWait() {
        if (isDone()) return;
        HandlerContext current = HandlerContexts.currentOrNull();
        if (current == null) return;
        if (!Thread.currentThread().isVirtual())
            throw new IllegalStateException("Platform-thread handlers cannot wait for unfinished RouteTask: " + route);
        if (HandlerContexts.belongsTo(owner) && (current.route().equals(route) || HandlerContexts.currentDomain() == domain))
            throw new IllegalStateException("Cannot wait for unfinished work in the current route execution domain: " + route);
    }

    public void join() { checkWait(); completion.join(); }
    public Void get() throws InterruptedException, ExecutionException { checkWait(); return completion.get(); }
    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        checkWait(); return completion.get(timeout, unit);
    }
    /** Accepted route tasks are not cancellable through this handle; timers may cancel before dispatch. */
    public boolean cancel(boolean interrupt) { return false; }
    public boolean isCancelled() { return completion.isCancelled(); }
    public boolean isDone() { return completion.isDone(); }
}
