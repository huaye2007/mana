package cn.managame.runtime.handler;

import cn.managame.network.connection.Connection;
import cn.managame.runtime.context.ClientHandlerContext;

/** Client ingress policy, called with the exact Handler's annotation-derived Domain. */
@FunctionalInterface
public interface HandlerContextFactory {
    /**
     * Select a nonzero Key and business identity from application state, preserving the supplied
     * Domain, message and borrowed Connection. Runs once on the submitting thread before Route
     * admission/scope binding. Must be thread-safe and must not access Route-owned mutable state.
     * Returning null or throwing rejects dispatch; no fallback or retry is performed.
     */
    ClientHandlerContext create(int domain, Connection connection, Object message);
}
