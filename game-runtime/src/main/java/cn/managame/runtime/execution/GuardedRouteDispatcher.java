package cn.managame.runtime.execution;

import cn.managame.runtime.route.Route;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Reject inline execution and prevent duplicate delivery with one guard object per batch. */
final class GuardedRouteDispatcher implements RouteDispatcher {
    private final RouteDispatcher delegate;
    GuardedRouteDispatcher(RouteDispatcher delegate) { this.delegate = delegate; }
    public void dispatch(Route route, Runnable batch) { submit(route, batch, false); }
    public void reschedule(Route route, Runnable batch) { submit(route, batch, true); }
    private void submit(Route route, Runnable batch, boolean continuation) {
        GuardedBatch guarded = new GuardedBatch(batch);
        try {
            if (continuation) delegate.reschedule(route, guarded);
            else delegate.dispatch(route, guarded);
            if (guarded.get() == -1) throw new IllegalStateException("RouteDispatcher ran inline");
        } finally { guarded.submitting = false; }
    }
    private static final class GuardedBatch extends AtomicInteger implements Runnable {
        private final Thread submitter = Thread.currentThread();
        private final Runnable action;
        private volatile boolean submitting = true;
        GuardedBatch(Runnable action) { this.action = action; }
        public void run() {
            if (Thread.currentThread() == submitter && submitting) {
                compareAndSet(0, -1);
                throw new IllegalStateException("RouteDispatcher must not run batches inline");
            }
            if (compareAndSet(0, 1)) action.run();
        }
    }
    public void shutdown() { delegate.shutdown(); }
    public boolean awaitTermination(Duration timeout) throws InterruptedException { return delegate.awaitTermination(timeout); }
    public int runningBatches() { return delegate.runningBatches(); }
    public int readyRoutes() { return delegate.readyRoutes(); }
}
