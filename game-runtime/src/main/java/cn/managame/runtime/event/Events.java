package cn.managame.runtime.event;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.internal.RuntimeContexts;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static publication through the current Runtime, or the process default bound at startup.
 *
 * <p>The default is bound once while the process starts and never changes: there is no unbind, and
 * closing a Runtime does not release it. That is deliberate. A binding that could be withdrawn would
 * have to define what happens to a publication that already selected the old Runtime, and would let a
 * replacement be installed under publishers that are mid-flight. Keeping it fixed means a publisher
 * either reaches the Runtime that was bound at startup or learns that Runtime is closed, which is the
 * truth either way.
 */
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

    public static void publish(Event event) {
        Objects.requireNonNull(event, "event");
        GameRuntime runtime = RuntimeContexts.ownerOrNull();
        if (runtime == null) runtime = DEFAULT.get();
        if (runtime == null) {
            throw new IllegalStateException("No current or default event Runtime; bind a Runtime during startup");
        }
        runtime.eventBus().publish(event);
    }

    /**
     * Clears the binding so another Runtime can be bound. <strong>For tests only.</strong> It is not a
     * lifecycle operation and no application should call it: a process binds once at startup and keeps
     * that binding, and withdrawing one at runtime reopens every question {@link #bind} exists to close.
     * It is public solely because a JVM that builds many Runtimes — which only a test suite does — has
     * no other way back to a clean state.
     */
    public static void resetForTests() {
        DEFAULT.set(null);
    }
}
