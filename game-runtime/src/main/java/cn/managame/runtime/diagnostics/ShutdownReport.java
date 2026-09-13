package cn.managame.runtime.diagnostics;

import cn.managame.runtime.route.Route;

import java.time.Duration;
import java.util.List;

/** Snapshot after stopping admission. Timeout does not interrupt running business methods. */
public record ShutdownReport(boolean terminated, List<PendingRoute> pendingRoutes,
                             int omittedRoutes, List<String> remainingDomains,
                             List<BackendFailure> backendFailures) {
    public ShutdownReport {
        pendingRoutes = List.copyOf(pendingRoutes);
        remainingDomains = List.copyOf(remainingDomains);
        backendFailures = List.copyOf(backendFailures);
    }
    public ShutdownReport(boolean terminated, List<PendingRoute> pendingRoutes,
                          int omittedRoutes, List<String> remainingDomains) {
        this(terminated, pendingRoutes, omittedRoutes, remainingDomains, List.of());
    }
    public record BackendFailure(String domain, String operation, Throwable cause) {}
    /** A null threadName denotes a queued route between executions; runningFor is then zero. */
    public record PendingRoute(String domain, Route route, String source, String threadName,
                               Duration runningFor, int queuedTasks) {}
}
