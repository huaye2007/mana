package cn.managame.runtime.executor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Active Routes stay pinned; Caffeine caches only idle mailboxes for bounded reuse. */
public final class VirtualThreadRouteExecutor implements RouteExecutor {
    private record Route(int domain, long key) {}
    private static final class Mailbox {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    }
    private final ConcurrentHashMap<Route, Mailbox> activeMailboxes = new ConcurrentHashMap<>();
    private final Cache<Route, Mailbox> idleMailboxes;
    // High bit means closed; remaining bits count reserved, queued and running tasks.
    private final AtomicLong admission = new AtomicLong();
    private final int capacity;

    public VirtualThreadRouteExecutor(int capacity) { this(capacity, Duration.ofMinutes(1)); }
    public VirtualThreadRouteExecutor(int capacity, Duration idleTimeout) { this(capacity, idleTimeout, System::nanoTime); }
    VirtualThreadRouteExecutor(int capacity, Duration idleTimeout, LongSupplier ticker) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        Objects.requireNonNull(idleTimeout); Objects.requireNonNull(ticker);
        long idleNanos;
        try { idleNanos = idleTimeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("idleTimeout exceeds nanosecond range", cause); }
        if (idleNanos <= 0) throw new IllegalArgumentException("idleTimeout must be positive");
        this.capacity = capacity;
        idleMailboxes = Caffeine.newBuilder().maximumSize(capacity).expireAfterWrite(idleNanos, TimeUnit.NANOSECONDS)
                .ticker(ticker::getAsLong).build();
    }

    public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
        Objects.requireNonNull(task);
        RouteExecuteStatus status = reserve();
        if (status != RouteExecuteStatus.ACCEPTED) return status;
        boolean submitted = false;
        try {
            Route route = new Route(domain, key);
            activeMailboxes.compute(route, (ignored, existing) -> {
                Mailbox cached = existing == null ? idleMailboxes.asMap().remove(route) : existing;
                Mailbox mailbox = cached == null ? new Mailbox() : cached;
                mailbox.queue.addLast(task);
                if (existing == null) {
                    try { Thread.ofVirtual().name("managame-route-" + domain + "-" + key).start(() -> drain(route)); }
                    catch (RuntimeException | Error cause) {
                        mailbox.queue.removeLast(); throw cause;
                    }
                }
                return mailbox;
            });
            submitted = true; return RouteExecuteStatus.ACCEPTED;
        } finally { if (!submitted) admission.decrementAndGet(); }
    }
    private RouteExecuteStatus reserve() {
        for (;;) {
            long state = admission.get();
            if (state < 0) return RouteExecuteStatus.CLOSED;
            if (state >= capacity) return RouteExecuteStatus.OVERLOADED;
            if (admission.compareAndSet(state, state + 1)) return RouteExecuteStatus.ACCEPTED;
        }
    }
    private boolean closed() { return admission.get() < 0; }

    private void drain(Route route) {
        // Each consumer owns its holder: a replacement consumer may start before this one returns.
        Runnable[] next = new Runnable[1];
        BiFunction<Route, Mailbox, Mailbox> poll = (ignored, current) -> {
            next[0] = current.queue.pollFirst();
            if (next[0] == null) {
                cacheIdle(route, current); return null;
            }
            return current;
        };
        for (;;) {
            activeMailboxes.computeIfPresent(route, poll);
            Runnable task = next[0]; next[0] = null;
            if (task == null) return;
            try { task.run(); } catch (Throwable cause) { StripedRouteExecutor.log(cause); }
            finally { admission.decrementAndGet(); }
        }
    }

    private void cacheIdle(Route route, Mailbox mailbox) {
        if (closed()) return;
        idleMailboxes.put(route, mailbox);
        // close may invalidate the cache just before this put; do not leave late idle entries behind.
        if (closed()) idleMailboxes.invalidate(route);
    }
    public void close() {
        long previous = admission.getAndUpdate(state -> state | Long.MIN_VALUE);
        if (previous < 0) return;
        idleMailboxes.invalidateAll();
    }
}
