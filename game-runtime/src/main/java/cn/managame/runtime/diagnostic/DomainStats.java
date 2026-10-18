package cn.managame.runtime.diagnostic;

import cn.managame.runtime.executor.RouteExecutorStats;

/**
 * One registered Domain's share of the Runtime's work, so "the instance is shedding" becomes "this
 * Domain is shedding". Cardinality is the number of registered Domains, fixed at build.
 *
 * @param rejected work refused after this Domain was resolved; a Runtime closed before any Domain is
 *                 known still counts in the Runtime total, so these need not sum to it
 * @param executor the limits of the executor bound to this Domain, which is the denominator for
 *                 {@code queued}; Domains sharing one executor repeat its numbers, so do not sum them
 */
public record DomainStats(int routeDomain, long queued, long running, long completed, long rejected,
                          RouteExecutorStats executor) {}
