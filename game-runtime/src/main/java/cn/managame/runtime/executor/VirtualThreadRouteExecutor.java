package cn.managame.runtime.executor;

import java.util.*;
public final class VirtualThreadRouteExecutor implements RouteExecutor {
    private record Route(int domain, long key) {}
    private final Map<Route, ArrayDeque<Runnable>> mailboxes = new HashMap<>();
    private final int capacity;
    private int pending;
    private boolean closed;
    public VirtualThreadRouteExecutor(int capacity) { if (capacity < 1) throw new IllegalArgumentException(); this.capacity = capacity; }
    public synchronized RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
        Objects.requireNonNull(task);
        if (closed) return RouteExecuteStatus.CLOSED;
        if (pending >= capacity) return RouteExecuteStatus.OVERLOADED;
        Route route = new Route(domain, key);
        ArrayDeque<Runnable> queue = mailboxes.get(route);
        boolean start = queue == null;
        if (start) { queue = new ArrayDeque<>(); mailboxes.put(route, queue); }
        queue.add(task); pending++;
        if (start) {
            try { Thread.ofVirtual().name("managame-route-" + domain + "-" + key).start(() -> drain(route)); }
            catch (RuntimeException | Error e) { mailboxes.remove(route); pending--; throw e; }
        }
        return RouteExecuteStatus.ACCEPTED;
    }
    private void drain(Route route) {
        for (;;) {
            Runnable task;
            synchronized (this) {
                var queue = mailboxes.get(route);
                task = queue.poll();
                if (task == null) { mailboxes.remove(route); return; }
            }
            try { task.run(); } catch (Throwable e) { StripedRouteExecutor.log(e); }
            finally { synchronized (this) { pending--; } }
        }
    }
    public synchronized void close() { closed = true; }
}
