package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.ExecutionDomainMetrics;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.HandlerExceptionHandler;
import cn.managame.runtime.diagnostics.RouteDiagnostics;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.diagnostics.RuntimeMetrics;
import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.diagnostics.SlowTask;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteType;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/** Routes entity identities to independently owned resource domains. */
final class RouteRuntime implements AutoCloseable {
    private static final Comparator<RouteDiagnostics> DIAGNOSTIC_ORDER = Comparator
            .comparing((RouteDiagnostics value) -> value.runningFor().compareTo(value.oldestQueuedFor()) >= 0
                    ? value.runningFor() : value.oldestQueuedFor())
            .thenComparingInt(RouteDiagnostics::queuedTasks);
    private final GameRuntime owner;
    private final HandlerExceptionHandler errors;
    private final Map<Class<? extends RouteType>, RouteDomain> byType;
    private final List<RouteDomain> domains;
    private final RouteDomain fallback;
    private final LongAdder rejected = new LongAdder();
    private volatile boolean closed;
    private final int slowTaskCapacity;

    RouteRuntime(GameRuntime owner, HandlerExceptionHandler errors, DomainLimits limits, int routeShards,
                 Map<Class<? extends RouteType>, ExecutionDomain> configuration, Duration slowTaskThreshold, int slowTaskCapacity) {
        this.owner = owner; this.errors = errors;
        this.slowTaskCapacity = slowTaskCapacity;
        Map<ExecutionDomain, RouteDomain> instances = new LinkedHashMap<>();
        if (configuration.isEmpty()) {
            var defaultConfiguration = ExecutionDomain.virtual("default").limits(limits).routeShards(routeShards).build();
            fallback = new RouteDomain(owner, this, defaultConfiguration, true, slowTaskThreshold, slowTaskCapacity);
            domains = List.of(fallback);
            byType = Map.of();
        } else {
            fallback = null;
            Map<Class<? extends RouteType>, RouteDomain> mapped = new HashMap<>();
            try {
                configuration.forEach((type, spec) -> mapped.put(type, instances.computeIfAbsent(spec,
                        value -> new RouteDomain(owner, this, value, false, slowTaskThreshold, slowTaskCapacity))));
            } catch (RuntimeException | Error failure) {
                List<RouteDomain> created = new ArrayList<>(instances.values()).reversed();
                created.forEach(RouteDomain::stopAdmission);
                ShutdownDeadline deadline = new ShutdownDeadline(Duration.ofSeconds(1));
                boolean interrupted = false;
                for (RouteDomain domain : created) {
                    try {
                        if (!domain.awaitTermination(deadline) && domain.backendFailures().isEmpty())
                            failure.addSuppressed(new IllegalStateException("Backend did not terminate: " + domain.name()));
                    } catch (InterruptedException error) { interrupted = true; failure.addSuppressed(error); }
                    domain.backendFailures().forEach(error -> { if (error.cause() != failure) failure.addSuppressed(error.cause()); });
                }
                if (interrupted) Thread.currentThread().interrupt();
                throw failure;
            }
            domains = List.copyOf(instances.values());
            byType = Map.copyOf(mapped);
        }
    }

    void requireType(Class<? extends RouteType> type) { domain(type); }
    RouteTask task(Route route) { return new RouteTask(owner, route, domain(route.type())); }

    private RouteDomain domain(Class<? extends RouteType> type) {
        Objects.requireNonNull(type);
        RouteDomain domain = byType.get(type);
        if (domain == null) domain = fallback;
        if (domain == null) throw new IllegalArgumentException("Missing execution domain: " + type.getName());
        return domain;
    }

    RouteTask submit(HandlerContext context, String source, Runnable action, boolean callback, RouteTask completion) {
        if (closed) {
            rejected.increment();
            throw new RuntimeClosedException();
        }
        return domain(context.routeType()).submit(context, source, action, callback, completion);
    }

    HandlerException report(String source, HandlerContext context, Throwable cause) {
        HandlerException failure = cause instanceof HandlerException h ? h : new HandlerException(source, context, cause);
        try { errors.handle(failure); }
        catch (Throwable reportingFailure) {
            try {
                System.getLogger(RouteRuntime.class.getName()).log(System.Logger.Level.ERROR,
                        "HandlerException observer failed", reportingFailure);
            } catch (Throwable ignored) { /* Observers must not strand an entity queue. */ }
        }
        return failure;
    }

    List<SlowTask> recentSlowTasks() {
        if (slowTaskCapacity == 0) return List.of();
        PriorityQueue<DomainDiagnostics.Sample> result = new PriorityQueue<>(DomainDiagnostics.ORDER);
        for (RouteDomain domain : domains) for (var sample : domain.slowTasks()) {
            if (result.size() < slowTaskCapacity) result.add(sample);
            else if (DomainDiagnostics.ORDER.compare(sample, result.peek()) > 0) {
                result.poll(); result.add(sample);
            }
        }
        return result.stream().sorted(DomainDiagnostics.ORDER).map(DomainDiagnostics.Sample::task).toList();
    }

    List<SlowTask> recentSlowTasks(Class<? extends RouteType> type) {
        return domain(type).slowTasks().stream().sorted(DomainDiagnostics.ORDER)
                .map(DomainDiagnostics.Sample::task).toList();
    }

    Optional<RouteDiagnostics> diagnostics(Route route) {
        return Optional.ofNullable(domain(route.type()).diagnostics(route));
    }

    List<RouteDiagnostics> diagnostics(int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("Diagnostic limit must be 1..10000");
        PriorityQueue<RouteDiagnostics> result = new PriorityQueue<>(DIAGNOSTIC_ORDER);
        for (RouteDomain domain : domains) domain.visitDiagnostics(snapshot -> {
            if (result.size() < limit) result.add(snapshot);
            else if (DIAGNOSTIC_ORDER.compare(snapshot, result.peek()) > 0) {
                result.poll(); result.add(snapshot);
            }
        });
        return result.stream().sorted(DIAGNOSTIC_ORDER.reversed()).toList();
    }

    int activeRoutes() { return domains.stream().mapToInt(RouteDomain::activeRoutes).sum(); }

    Map<String, ExecutionDomainMetrics> executionDomains() {
        Map<String, ExecutionDomainMetrics> snapshots = new LinkedHashMap<>();
        for (var domain : domains) {
            var snapshot = domain.domainMetrics();
            snapshots.put(snapshot.name(), snapshot);
        }
        return Map.copyOf(snapshots);
    }

    RuntimeMetrics metrics(int timers, long timerRejections) {
        int outstanding = 0, active = 0;
        long completed = 0, failed = 0, rejectedTasks = rejected.sum() + timerRejections;
        long queue = 0, maxQueue = 0, execution = 0, maxExecution = 0;
        for (var domain : domains) {
            RuntimeMetrics m = domain.metrics(0, 0);
            outstanding += m.outstandingTasks(); active += m.activeRoutes();
            completed += m.completedTasks(); failed += m.failedTasks(); rejectedTasks += m.rejectedTasks();
            queue += m.totalQueueNanos(); maxQueue = Math.max(maxQueue, m.maxQueueNanos());
            execution += m.totalExecutionNanos(); maxExecution = Math.max(maxExecution, m.maxExecutionNanos());
        }
        return new RuntimeMetrics(outstanding, active, timers, completed, failed, rejectedTasks,
                queue, maxQueue, execution, maxExecution);
    }

    ShutdownReport close(ShutdownDeadline deadline) { return close(deadline, domains); }

    ShutdownReport abortStartup(ShutdownDeadline deadline) { return close(deadline, domains.reversed()); }

    private ShutdownReport close(ShutdownDeadline deadline, List<RouteDomain> shutdownOrder) {
        if (HandlerContexts.belongsTo(owner)) throw new IllegalStateException("Cannot close runtime from its own handler");
        closed = true;
        shutdownOrder.forEach(RouteDomain::stopAdmission);
        List<String> remaining = new ArrayList<>();
        boolean interrupted = false;
        for (RouteDomain domain : shutdownOrder) {
            try {
                if (!domain.awaitTermination(deadline)) remaining.add(domain.name());
            } catch (InterruptedException error) {
                interrupted = true;
                remaining.add(domain.name());
            }
        }
        List<ShutdownReport.PendingRoute> pending = new ArrayList<>();
        for (RouteDomain domain : domains) pending.addAll(domain.pendingRoutes(100 - pending.size()));
        if (interrupted) Thread.currentThread().interrupt();
        List<ShutdownReport.BackendFailure> backendFailures = domains.stream()
                .flatMap(domain -> domain.backendFailures().stream()).toList();
        return new ShutdownReport(remaining.isEmpty() && backendFailures.isEmpty(), pending,
                Math.max(0, activeRoutes() - pending.size()), remaining, backendFailures);
    }

    @Override public void close() {
        ShutdownReport report = close(new ShutdownDeadline(Duration.ofSeconds(30)));
        if (!report.terminated()) throw new RuntimeShutdownException(report);
    }
}
