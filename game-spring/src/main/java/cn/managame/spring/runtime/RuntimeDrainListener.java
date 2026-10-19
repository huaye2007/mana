package cn.managame.spring.runtime;

import cn.managame.runtime.GameRuntime;
import cn.managame.boot.lifecycle.ShutdownBudget;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.*;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/**
 * Synchronous ContextClosedEvent barrier, before ordinary listeners and Bean destruction, running the first
 * two phases of {@link ShutdownBudget}.
 *
 * <p>The lead comes first and is the whole reason this runs before admission closes: readiness reports
 * failure while work is still admitted, so a load balancer can notice and stop sending before anything is
 * refused. Work that arrives during the lead is served normally, and a Timer or Cron due in that window
 * still fires.
 *
 * <p>Admission then closes and the drain waits for admitted Route work and captured callbacks, after which
 * the Runtime is closed even if something never completed, so a callback nobody finishes cannot hold the
 * process open. Closing admission also stops the Timer and Cron wheels, which is deliberate: once admission
 * is closed a fired Timer's submission is refused with RUNTIME_CLOSED and dropped, so keeping the wheels
 * running through the drain would spin and discard rather than execute anything. Durable timers that outlive
 * a restart are a separate, unimplemented capability.
 *
 * <p>The third phase, the final persistence pass, belongs to Data and runs afterwards during Bean
 * destruction with the reserved slice this listener leaves it.
 */
final class RuntimeDrainListener implements ApplicationListener<ContextClosedEvent>, Ordered {
    private static final System.Logger LOG = System.getLogger(RuntimeDrainListener.class.getName());
    private final GameRuntime runtime;
    private final ApplicationContext owner;
    private final ShutdownBudget budget;
    /** Set once at the very start of shutdown, before admission closes; read from any thread. */
    private volatile boolean leaving;

    RuntimeDrainListener(GameRuntime runtime, ApplicationContext owner, ShutdownBudget budget) {
        this.runtime = runtime; this.owner = owner; this.budget = budget;
    }

    /**
     * Whether this instance has begun leaving rotation. This is the piece that makes a readiness endpoint
     * useful for taking an instance out of rotation rather than only for noticing a sick one: without it,
     * admission and readiness change in the same instant and everything a load balancer sends between
     * that instant and its next probe is refused. Readable from an HTTP thread answering a probe.
     */
    boolean leavingRotation() { return leaving; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }

    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != owner) return;
        boolean interrupted = beginLeavingRotation();
        runtime.shutdown();
        boolean drained = false;
        try { drained = runtime.awaitTermination(budget.drain()); }
        catch (InterruptedException failure) { interrupted = true; }
        try {
            if (!drained) LOG.log(System.Logger.Level.WARNING,
                    "Runtime did not drain within {0}{1}; closing with {2} admitted tasks or captured callbacks"
                            + " unfinished, leaving {3} for the final persistence pass",
                    budget.drain(), interrupted ? " (interrupted)" : "", runtime.stats().inFlight(), budget.flushReserve());
            runtime.close();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /** Reports unreadiness, then waits the lead while still admitting. Returns whether it was interrupted. */
    private boolean beginLeavingRotation() {
        leaving = true;
        Duration lead = budget.lead();
        if (lead.isZero()) return false;
        LOG.log(System.Logger.Level.INFO, "Leaving rotation; readiness reports failure for {0} before admission closes", lead);
        try { TimeUnit.NANOSECONDS.sleep(lead.toNanos()); return false; }
        catch (InterruptedException interrupted) { return true; }
    }
}
