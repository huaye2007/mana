package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.ExecutionDomainMetrics;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RouteDiagnostics;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.diagnostics.RuntimeMetrics;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.route.Route;

import java.util.*;
import java.util.function.Consumer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Independent entity queues, scheduled in finite batches inside one resource domain. */
final class RouteDomain {
    private static final class Work {
        final HandlerContext context;
        final String source;
        final Runnable action;
        final RouteTask result;
        final long enqueuedAt = System.nanoTime();
        HandlerException failure;
        long startedAt;
        String threadName;
        Work(HandlerContext context, String source, Runnable action, RouteTask result) {
            this.context = context; this.source = source; this.action = action; this.result = result;
        }
    }

    private static final class Lane {
        final ArrayDeque<Work> queue = new ArrayDeque<>();
        int outstanding;
        Work current;
    }

    private static final class Shard {
        final Map<Route, Lane> lanes = new HashMap<>();
    }

    private final GameRuntime owner;
    private final RouteRuntime runtime;
    private final DomainLimits limits;
    private final RouteDispatcher workers;
    private final DomainDiagnostics diagnostics;
    private final ExecutionDomain configuration;
    private final boolean legacyCapacity;
    private final Object drained = new Object();
    private final Shard[] shards;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final LongAdder completed = new LongAdder(), failed = new LongAdder(), rejected = new LongAdder();
    private final LongAdder queueNanos = new LongAdder(), executionNanos = new LongAdder();
    private final LongAccumulator maxQueueNanos = new LongAccumulator(Long::max, 0);
    private final LongAccumulator maxExecutionNanos = new LongAccumulator(Long::max, 0);
    private volatile boolean closed;
    private boolean shutdownRequested;
    private final ConcurrentMap<String, Throwable> backendFailures = new ConcurrentHashMap<>();

    RouteDomain(GameRuntime owner, RouteRuntime runtime, ExecutionDomain configuration, boolean legacyCapacity,
                Duration slowTaskThreshold, int slowTaskCapacity) {
        this.owner = owner; this.runtime = runtime; this.configuration = configuration;
        this.limits = configuration.limits(); this.legacyCapacity = legacyCapacity;
        shards = new Shard[configuration.routeShards()];
        Arrays.setAll(shards, ignored -> new Shard());
        diagnostics = new DomainDiagnostics(slowTaskThreshold, slowTaskCapacity);
        // All non-resource construction precedes acquiring the owned backend.
        this.workers = configuration.createDispatcher();
    }

    RouteTask submit(HandlerContext context, String source, Runnable action, boolean callback, RouteTask completion) {
        Objects.requireNonNull(action);
        var gate = lifecycle.readLock();
        gate.lock();
        try {
            if (closed) {
                rejected.increment();
                throw new RuntimeClosedException();
            }
            Route route = context.route();
            Shard shard = shards[Math.floorMod(route.hashCode(), shards.length)];
            synchronized (shard) {
                Lane lane = shard.lanes.get(route);
                boolean start = lane == null;
                if (start) lane = new Lane();
                int routeLimit = limits.maxTasksPerRoute() + (callback ? limits.callbackReservePerRoute() : 0);
                if (lane.outstanding >= routeLimit) throw overloaded(RuntimeOverloadedException.Reason.ROUTE_CAPACITY);
                int totalLimit = limits.maxTasks() + (callback ? limits.callbackReserve() : 0);
                acquire(totalLimit);

                RouteTask result = completion != null ? completion : new RouteTask(owner, route, this);
                Work work = new Work(context, source, action, result);
                lane.queue.addLast(work);
                lane.outstanding++;
                if (start) {
                    shard.lanes.put(route, lane);
                    active.incrementAndGet();
                    Lane selected = lane;
                    try { workers.dispatch(route, () -> drain(shard, route, selected)); }
                    catch (RuntimeException | Error failure) {
                        shard.lanes.remove(route);
                        routeFinished();
                        outstanding.decrementAndGet();
                        rejected.increment();
                        throw failure;
                    }
                }
                return result;
            }
        } finally { gate.unlock(); }
    }

    private void acquire(int limit) {
        int value;
        do {
            value = outstanding.get();
            if (value >= limit) throw overloaded(legacyCapacity ? RuntimeOverloadedException.Reason.GLOBAL_CAPACITY : RuntimeOverloadedException.Reason.DOMAIN_CAPACITY);
        } while (!outstanding.compareAndSet(value, value + 1));
    }

    private RuntimeOverloadedException overloaded(RuntimeOverloadedException.Reason reason) {
        rejected.increment();
        return new RuntimeOverloadedException(reason);
    }

    private void drain(Shard shard, Route route, Lane lane) {
        for (int turn = 0; turn < configuration.tasksPerTurn(); turn++) {
            Work work;
            synchronized (shard) {
                work = lane.queue.pollFirst();
                if (work == null) {
                    shard.lanes.remove(route);
                    routeFinished();
                    return;
                }
                lane.current = work; work.startedAt = System.nanoTime(); work.threadName = Thread.currentThread().getName();
            }
            long start = work.startedAt;
            long wait = start - work.enqueuedAt;
            queueNanos.add(wait);
            maxQueueNanos.accumulate(wait);
            try {
                HandlerContexts.run(owner, this, work.context, () -> {
                    try { work.action.run(); }
                    catch (Throwable cause) { work.failure = runtime.report(work.source, work.context, cause); }
                });
            } catch (Throwable cause) {
                // Also release admission if establishing the dynamic scope itself fails.
                work.failure = runtime.report(work.source, work.context, cause);
            } finally {
                long duration = System.nanoTime() - start;
                executionNanos.add(duration);
                maxExecutionNanos.accumulate(duration);
                synchronized (shard) {
                    lane.current = null;
                    lane.outstanding--;
                    outstanding.decrementAndGet();
                }
                diagnostics.record(name(), work.context, work.source, work.threadName,
                        wait, duration, work.failure != null);
                // Tasks share a worker while a route stays busy; interruption must not leak.
                Thread.interrupted();
            }
            if (work.failure == null) { completed.increment(); work.result.succeed(); }
            else { failed.increment(); work.result.fail(work.failure); }
        }
        List<Work> stranded = null;
        Throwable schedulingFailure = null;
        synchronized (shard) {
            if (lane.queue.isEmpty()) {
                shard.lanes.remove(route);
                routeFinished();
            } else {
                try { workers.reschedule(route, () -> drain(shard, route, lane)); }
                catch (RuntimeException | Error failure) {
                    schedulingFailure = new IllegalStateException("RouteDispatcher failed to continue an admitted entity", failure);
                    stranded = new ArrayList<>(lane.queue);
                    lane.queue.clear();
                    outstanding.addAndGet(-stranded.size());
                    shard.lanes.remove(route);
                    routeFinished();
                }
            }
        }
        if (stranded != null) for (Work work : stranded) {
            failed.increment();
            work.result.fail(runtime.report("route batch scheduling: " + work.source, work.context, schedulingFailure));
        }
    }

    private void routeFinished() {
        if (active.decrementAndGet() == 0) {
            if (closed) shutdownWorkers();
            synchronized (drained) { drained.notifyAll(); }
        }
    }

    ExecutionDomainMetrics domainMetrics() {
        return new ExecutionDomainMetrics(configuration.name(), configuration.mode(), configuration.scheduling(),
                workers.runningBatches(), workers.readyRoutes(), metrics(0, 0));
    }

    int activeRoutes() { return active.get(); }

    RuntimeMetrics metrics(int scheduledTimers, long timerRejections) {
        return new RuntimeMetrics(outstanding.get(), active.get(), scheduledTimers,
                completed.sum(), failed.sum(), rejected.sum() + timerRejections,
                queueNanos.sum(), maxQueueNanos.get(), executionNanos.sum(), maxExecutionNanos.get());
    }

    void stopAdmission() {
        if (HandlerContexts.belongsTo(owner)) throw new IllegalStateException("Cannot close runtime from its own handler");
        var gate = lifecycle.writeLock();
        gate.lock();
        try { closed = true; }
        finally { gate.unlock(); }
        if (active.get() == 0) shutdownWorkers();
    }

    boolean awaitTermination(ShutdownDeadline deadline) throws InterruptedException {
        synchronized (drained) {
            while (active.get() != 0) {
                long nanos = deadline.remainingNanos();
                if (nanos == 0) return false;
                TimeUnit.NANOSECONDS.timedWait(drained, nanos);
            }
        }
        shutdownWorkers();
        if (backendFailures.containsKey("shutdown")) return false;
        try { return workers.awaitTermination(deadline.remaining()); }
        catch (RuntimeException | Error failure) {
            backendFailures.putIfAbsent("awaitTermination", failure);
            return false;
        }
    }

    private synchronized void shutdownWorkers() {
        if (shutdownRequested) return;
        shutdownRequested = true;
        try { workers.shutdown(); }
        catch (RuntimeException | Error failure) { backendFailures.putIfAbsent("shutdown", failure); }
    }

    List<ShutdownReport.BackendFailure> backendFailures() {
        return backendFailures.entrySet().stream()
                .map(entry -> new ShutdownReport.BackendFailure(name(), entry.getKey(), entry.getValue())).toList();
    }

    String name() { return configuration.name(); }

    List<DomainDiagnostics.Sample> slowTasks() { return diagnostics.snapshot(); }

    RouteDiagnostics diagnostics(Route route) {
        Shard shard = shards[Math.floorMod(route.hashCode(), shards.length)];
        QueueSnapshot snapshot;
        synchronized (shard) {
            Lane lane = shard.lanes.get(route);
            snapshot = lane == null ? null : capture(route, lane, System.nanoTime());
        }
        return snapshot == null ? null : snapshot.describe(name());
    }

    void visitDiagnostics(Consumer<RouteDiagnostics> visitor) {
        for (Shard shard : shards) {
            List<QueueSnapshot> captured;
            synchronized (shard) {
                captured = new ArrayList<>(shard.lanes.size());
                long now = System.nanoTime();
                for (var entry : shard.lanes.entrySet())
                    captured.add(capture(entry.getKey(), entry.getValue(), now));
            }
            // Duration conversion, aggregation and ranking never hold an entity queue lock.
            for (QueueSnapshot snapshot : captured) visitor.accept(snapshot.describe(name()));
        }
    }

    private QueueSnapshot capture(Route route, Lane lane, long now) {
        Work running = lane.current, queued = lane.queue.peekFirst();
        return new QueueSnapshot(route, running == null ? null : running.source,
                running == null ? null : running.threadName, running == null ? 0 : now - running.startedAt,
                lane.queue.size(), queued == null ? null : queued.source, queued == null ? 0 : now - queued.enqueuedAt);
    }

    /** Immutable scalar capture: never retains a Lane, Work, action or business context. */
    private record QueueSnapshot(Route route, String source, String thread, long runningNanos,
                                 int queued, String queuedSource, long waitingNanos) {
        RouteDiagnostics describe(String domain) {
            return new RouteDiagnostics(domain, route, source, thread,
                    Duration.ofNanos(Math.max(0, runningNanos)), queued, queuedSource,
                    Duration.ofNanos(Math.max(0, waitingNanos)));
        }
    }
    List<ShutdownReport.PendingRoute> pendingRoutes(int limit) {
        List<ShutdownReport.PendingRoute> result = new ArrayList<>();
        for (Shard shard : shards) synchronized (shard) {
            for (var entry : shard.lanes.entrySet()) {
                if (result.size() >= limit) return result;
                Lane lane = entry.getValue();
                Work work = lane.current != null ? lane.current : lane.queue.peekFirst();
                result.add(new ShutdownReport.PendingRoute(name(), entry.getKey(), work == null ? "handoff" : work.source,
                        lane.current == null ? null : work.threadName,
                        lane.current == null ? Duration.ZERO : Duration.ofNanos(Math.max(0, System.nanoTime() - work.startedAt)),
                        lane.queue.size()));
            }
        }
        return result;
    }
}
