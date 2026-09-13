package cn.managame.runtime.execution;

import cn.managame.runtime.route.Route;

import java.time.Duration;

/**
 * Scheduling backend for serialized entity batches, exclusively owned by one Runtime.
 * Both submission methods must be thread-safe, asynchronous and nonblocking; never caller-runs.
 * Accepted batches execute exactly once. Never throw after acceptance or silently discard work.
 * Runtime retains entity FIFO/exclusivity. A factory creates a fresh backend for each Runtime.
 */
public interface RouteDispatcher {
    /** First activation of an entity. May reject before acceptance when ingress is full. */
    void dispatch(Route route, Runnable batch);
    /**
     * Continue an already admitted entity, called by its current batch before returning.
     * Must retain scheduling capacity: queue saturation must never reject a continuation.
     * Ready continuations are bounded by the Runtime's admitted entity count.
     */
    void reschedule(Route route, Runnable batch);
    /** Nonblocking and idempotent; called after admitted entity queues drain. */
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    /** Return -1 when this backend does not expose this metric. */
    default int runningBatches() { return -1; }
    default int readyRoutes() { return -1; }
}
