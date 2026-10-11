package cn.managame.spring.runtime;

import cn.managame.runtime.GameRuntime;
import org.springframework.context.*;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import java.time.Duration;

/**
 * Synchronous ContextClosedEvent barrier, before ordinary listeners and Bean destruction. Waits at most
 * {@code drainTimeout} for admitted Route work and captured callbacks, then closes the Runtime anyway so a
 * callback that is never completed cannot keep the process from shutting down.
 */
final class RuntimeDrainListener implements ApplicationListener<ContextClosedEvent>, Ordered {
    private static final System.Logger LOG = System.getLogger(RuntimeDrainListener.class.getName());
    private final GameRuntime runtime;
    private final ApplicationContext owner;
    private final Duration drainTimeout;
    RuntimeDrainListener(GameRuntime runtime, ApplicationContext owner, Duration drainTimeout) {
        this.runtime = runtime; this.owner = owner; this.drainTimeout = drainTimeout;
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }
    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != owner) return;
        runtime.shutdown();
        boolean drained = false, interrupted = false;
        try { drained = runtime.awaitTermination(drainTimeout); }
        catch (InterruptedException failure) { interrupted = true; }
        try {
            if (!drained) LOG.log(System.Logger.Level.WARNING,
                    "Runtime did not drain within {0}{1}; closing with {2} admitted tasks or captured callbacks unfinished",
                    drainTimeout, interrupted ? " (interrupted)" : "", runtime.stats().inFlight());
            runtime.close();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
