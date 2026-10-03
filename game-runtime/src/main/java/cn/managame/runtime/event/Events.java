package cn.managame.runtime.event;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.internal.RuntimeContexts;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Static publication through the current Runtime, or an explicitly bound external-thread default. */
public final class Events {
    private static final AtomicReference<GameRuntime> DEFAULT = new AtomicReference<>();

    private Events() {}

    /** Bootstrap hook: borrows a live Runtime without taking ownership. Bind before starting publishers. */
    public static void bind(GameRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        GameRuntime previous = DEFAULT.compareAndExchange(null, runtime);
        if (previous != null && previous != runtime) {
            throw new IllegalStateException("A default event Runtime is already bound");
        }
    }

    /** Removes only this exact Runtime's default binding; does not close it or cancel accepted events. */
    public static boolean unbind(GameRuntime runtime) {
        return DEFAULT.compareAndSet(Objects.requireNonNull(runtime, "runtime"), null);
    }

    public static void publish(Event event) {
        Objects.requireNonNull(event, "event");
        GameRuntime runtime = RuntimeContexts.ownerOrNull();
        if (runtime == null) runtime = DEFAULT.get();
        if (runtime == null) {
            throw new IllegalStateException("No current or default event Runtime; bind a Runtime during startup");
        }
        runtime.eventBus().publish(event);
    }
}
