package cn.managame.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /**
     * A derived value recomputed only when the snapshot version changes.
     *
     * <p>Use this for anything read per tick or per request, so the string is parsed once per config
     * change instead of once per read.</p>
     */
    default <T> ConfigRef<T> ref(Function<ConfigSnapshot, T> mapper) {
        return new ConfigRef<>(this::snapshot, mapper);
    }

    /**
     * Maps every key of the current snapshot to the name of the layer that won it.
     *
     * <p>Precedence is declaration order — a later layer overrides an earlier one — which is easy to
     * set but hard to verify once a stack has a few layers. This answers "who actually supplied this
     * value", so a surprising value can be traced without bisecting the configuration.</p>
     *
     * <p>Diagnostic: it walks every layer, so log it at startup or expose it on an admin endpoint
     * rather than calling it on a hot path.</p>
     */
    default Map<String, String> origins() { return Map.of(); }

    /**
     * Every layer that supplies {@code key}, in increasing precedence order.
     *
     * <p>The last entry is the value visible through {@link #snapshot()}; the earlier ones are the
     * values it overrode. Empty when no layer defines the key.</p>
     *
     * {@snippet :
     * config.explain("game.server.port");   // [local=8080, nacos=9000, env=9090]
     * }
     */
    default List<ConfigOrigin> explain(String key) { return List.of(); }

    /** True when every source is reachable, the data is fresh and the last update was accepted. */
    default boolean isHealthy() { return true; }

    /** The reason {@link #isHealthy()} is false, when there is one. */
    default Optional<Throwable> lastError() { return Optional.empty(); }

    @Override
    void close();
}
