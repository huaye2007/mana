package cn.managame.runtime.diagnostics;

import cn.managame.runtime.route.Route;

import java.time.Duration;


/** Approximate live snapshot; no request, connection or mutable business state is retained. */
public record RouteDiagnostics(String domain, Route route, String runningSource, String threadName,
                               Duration runningFor, int queuedTasks, String oldestQueuedSource,
                               Duration oldestQueuedFor) {

}
