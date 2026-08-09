package cn.managame.config;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A derived config value that is recomputed only when the snapshot changes.
 *
 * <p>Typed accessors on {@link ConfigSnapshot} parse their string on every call, which shows up when
 * a value is read on a per-tick path. A ref pays that cost once per snapshot version and then serves
 * reads from a single volatile field:</p>
 *
 * {@snippet :
 * ConfigRef<Integer> tickMillis = config.ref(snapshot -> snapshot.getInt("game.tick.millis", 50));
 * // per tick
 * int millis = tickMillis.get();
 * }
 *
 * <p>{@code get()} is safe from any thread. Under a concurrent version change the mapper may run more
 * than once; it must therefore be side-effect free. The value returned always corresponds to some
 * snapshot the center actually published, never to a mix of two.</p>
 */
public final class ConfigRef<T> implements Supplier<T> {
    private final Supplier<ConfigSnapshot> snapshots;
    private final Function<ConfigSnapshot, T> mapper;
    private volatile Derived<T> derived;

    ConfigRef(Supplier<ConfigSnapshot> snapshots, Function<ConfigSnapshot, T> mapper) {
        this.snapshots = snapshots;
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns the derived value for the current snapshot, recomputing only after a version change. */
    @Override public T get() {
        ConfigSnapshot current = snapshots.get();
        Derived<T> cached = derived;
        if (cached != null && cached.version() == current.version()) return cached.value();
        T value = mapper.apply(current);
        derived = new Derived<>(current.version(), value);
        return value;
    }

    /** The snapshot version the currently cached value was derived from, or {@code 0} before first use. */
    public long version() {
        Derived<T> cached = derived;
        return cached == null ? 0 : cached.version();
    }

    private record Derived<T>(long version, T value) { }
}
