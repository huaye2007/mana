package cn.managame.runtime.diagnostics;

import java.util.concurrent.RejectedExecutionException;

/** Local admission failure. Transport adapters decide how to map it to a wire error. */
public final class RuntimeOverloadedException extends RejectedExecutionException {
    public enum Reason { GLOBAL_CAPACITY, DOMAIN_CAPACITY, ROUTE_CAPACITY, TIMER_CAPACITY }

    private final Reason reason;
    public RuntimeOverloadedException(Reason reason) {
        super("Runtime admission rejected: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
