package cn.managame.config.source;

import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A source whose values are driven from code rather than a backend.
 *
 * <p>This is the seam for testing anything that reads config: build one, hand it to
 * {@link cn.managame.config.ConfigFactory#open(ConfigSource) ConfigFactory.open}, and drive updates
 * with {@link #emit} or failures with {@link #fail}, without a file, a container or an SPI file.</p>
 *
 * {@snippet :
 * MemoryConfigSource source = new MemoryConfigSource(Map.of("game.server.port", "8080"));
 * try (ConfigCenter config = ConfigFactory.open(source)) {
 *     source.emit(Map.of("game.server.port", "9090"));
 * }
 * }
 *
 * <p>Instances registered with {@link #named(String)} are also reachable as a {@code memory} layer,
 * which lets a test drive one layer of a stack that otherwise looks like production.</p>
 */
public final class MemoryConfigSource implements ConfigSource {
    private static final ConcurrentHashMap<String, MemoryConfigSource> REGISTRY = new ConcurrentHashMap<>();

    private final AtomicReference<Map<String, String>> values;
    private final AtomicReference<Consumer<Map<String, String>>> updates = new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> errors = new AtomicReference<>();
    private final AtomicReference<Throwable> loadFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String name;

    public MemoryConfigSource() { this(Map.of()); }

    public MemoryConfigSource(Map<String, String> initial) { this(null, initial); }

    private MemoryConfigSource(String name, Map<String, String> initial) {
        this.name = name;
        values = new AtomicReference<>(Map.copyOf(initial));
    }

    /**
     * Returns the shared source registered under {@code name}, creating an empty one on first use.
     *
     * <p>Registered sources outlive the centers that read them, so a test can publish values before
     * the center opens and keep publishing after. Call {@link #unregister(String)} when done.</p>
     */
    public static MemoryConfigSource named(String name) {
        return REGISTRY.computeIfAbsent(java.util.Objects.requireNonNull(name, "name"),
                key -> new MemoryConfigSource(key, Map.of()));
    }

    /** Drops the registration created by {@link #named(String)}. */
    public static void unregister(String name) { REGISTRY.remove(name); }

    /** Publishes a complete replacement snapshot to the watching center, if any. */
    public void emit(Map<String, String> next) {
        Map<String, String> immutable = Map.copyOf(next);
        values.set(immutable);
        loadFailure.set(null);
        Consumer<Map<String, String>> listener = updates.get();
        if (listener != null) listener.accept(immutable);
    }

    /** Makes the next loads fail with {@code error} and reports it as a terminal watch failure. */
    public void fail(Throwable error) {
        loadFailure.set(java.util.Objects.requireNonNull(error, "error"));
        Consumer<Throwable> listener = errors.get();
        if (listener != null) listener.accept(error);
    }

    /** The values this source would return from the next load. */
    public Map<String, String> values() { return values.get(); }

    @Override public Map<String, String> load() throws Exception {
        Throwable failure = loadFailure.get();
        if (failure instanceof Exception exception) throw exception;
        if (failure != null) throw new IllegalStateException(failure);
        return values.get();
    }

    @Override public ConfigData loadData() throws Exception { return ConfigData.unversioned(load()); }

    @Override public void ping() { }

    @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
        if (closed.get()) throw new IllegalStateException("memory config source is closed");
        if (!updates.compareAndSet(null, onUpdate)) {
            throw new IllegalStateException("memory config watch is already active");
        }
        errors.set(onError);
        return this::stopWatch;
    }

    private void stopWatch() {
        updates.set(null);
        errors.set(null);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            stopWatch();
            if (name != null) REGISTRY.remove(name, this);
        }
    }
}
