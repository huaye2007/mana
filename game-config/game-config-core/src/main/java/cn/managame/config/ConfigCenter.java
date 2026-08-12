package cn.managame.config;

import java.util.Optional;
import java.util.function.Consumer;

/** A live view of merged config, with change notification and health reporting. */
public interface ConfigCenter extends AutoCloseable {
    /**
     * The current immutable snapshot. Lock-free and allocation-free; safe to call on a hot path.
     *
     * <p>Remains readable after {@link #close()} so shutdown code does not have to order itself
     * around the center.</p>
     */
    ConfigSnapshot snapshot();

    /** Reloads every source and publishes the result if it differs from the current snapshot. */
    ConfigSnapshot reload();

    /** Registers a change listener. Equivalent to {@code listen(listener, false)}. */
    AutoCloseable listen(Consumer<ConfigChange> listener);

    /**
     * Registers a change listener, optionally delivering the current snapshot first.
     *
     * <p>With {@code deliverCurrent}, registration and delivery happen atomically with respect to
     * updates, which removes the gap between reading {@link #snapshot()} and subscribing where a
     * change would be missed. The initial event carries an empty {@code previous} snapshot, so
     * {@link ConfigChange#changedKeys()} lists every key and one code path can handle both the
     * initial state and later updates.</p>
     */
    AutoCloseable listen(Consumer<ConfigChange> listener, boolean deliverCurrent);

    /** True when the backend is reachable, the data is fresh and the last update was accepted. */
    default boolean isHealthy() { return true; }

    /** The reason {@link #isHealthy()} is false, when there is one. */
    default Optional<Throwable> lastError() { return Optional.empty(); }

    @Override
    void close();
}
