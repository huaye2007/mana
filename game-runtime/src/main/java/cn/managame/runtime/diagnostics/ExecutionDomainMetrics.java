package cn.managame.runtime.diagnostics;

import cn.managame.runtime.execution.ExecutionDomain;

/** Approximate concurrent snapshot. Running batches include short scheduling handoffs. */
public record ExecutionDomainMetrics(String name, ExecutionDomain.Mode mode, ExecutionDomain.Scheduling scheduling,
                                     int runningBatches, int readyRoutes, RuntimeMetrics tasks) {}
