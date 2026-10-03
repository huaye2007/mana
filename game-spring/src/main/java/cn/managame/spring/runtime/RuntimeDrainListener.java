package cn.managame.spring.runtime;

import cn.managame.runtime.GameRuntime;
import org.springframework.context.*;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import java.time.Duration;

/** Synchronous ContextClosedEvent barrier, before ordinary listeners and Bean destruction. */
final class RuntimeDrainListener implements ApplicationListener<ContextClosedEvent>, Ordered {
    private final GameRuntime runtime;
    private final ApplicationContext owner;
    RuntimeDrainListener(GameRuntime runtime, ApplicationContext owner) { this.runtime = runtime; this.owner = owner; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }
    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != owner) return;
        runtime.shutdown();
        boolean interrupted = false;
        try {
            while (true) {
                try { if (runtime.awaitTermination(Duration.ofSeconds(30))) break; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            runtime.close();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
