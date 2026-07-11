package cn.managame.config;

import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ConfigFactory {
    private ConfigFactory() { }

    public static ConfigCenter open(ConfigOptions options) {
        String type = options.type().toLowerCase(Locale.ROOT);
        ConfigProvider provider = ServiceLoader.load(ConfigProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.type().toLowerCase(Locale.ROOT).equals(type))
                .findFirst()
                .orElseThrow(() -> new ConfigException("config provider is not available: " + type));
        return DefaultConfigCenter.open(provider.create(options));
    }

    static final class DefaultConfigCenter implements ConfigCenter {
        private static final Logger log = LoggerFactory.getLogger(DefaultConfigCenter.class);
        private final ConfigSource source;
        private final AtomicReference<ConfigSnapshot> snapshot;
        private final CopyOnWriteArrayList<Consumer<ConfigChange>> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private AutoCloseable watch;

        private DefaultConfigCenter(ConfigSource source, Map<String, String> initial) {
            this.source = source;
            snapshot = new AtomicReference<>(new ConfigSnapshot(1, Instant.now(), initial));
        }

        static ConfigCenter open(ConfigSource source) {
            try {
                DefaultConfigCenter center = new DefaultConfigCenter(source, source.load());
                center.watch = source.watch(center::apply, error -> log.warn("config watch failed", error));
                return center;
            } catch (Exception e) {
                try { source.close(); } catch (Exception closeError) { e.addSuppressed(closeError); }
                throw new ConfigException("cannot open config center", e);
            }
        }

        @Override public ConfigSnapshot snapshot() { ensureOpen(); return snapshot.get(); }

        @Override public ConfigSnapshot reload() {
            ensureOpen();
            try { return apply(source.load()); }
            catch (Exception e) { throw new ConfigException("cannot reload config", e); }
        }

        @Override public AutoCloseable listen(Consumer<ConfigChange> listener) {
            ensureOpen();
            listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
            return () -> listeners.remove(listener);
        }

        private synchronized ConfigSnapshot apply(Map<String, String> values) {
            if (closed.get()) return snapshot.get();
            Map<String, String> immutable = Map.copyOf(values);
            ConfigSnapshot previous = snapshot.get();
            if (previous.values().equals(immutable)) return previous;
            ConfigSnapshot current = new ConfigSnapshot(previous.version() + 1, Instant.now(), immutable);
            snapshot.set(current);
            Set<String> changed = new HashSet<>(previous.values().keySet());
            changed.addAll(current.values().keySet());
            changed.removeIf(key -> java.util.Objects.equals(previous.get(key), current.get(key)));
            ConfigChange event = new ConfigChange(previous, current, changed);
            for (Consumer<ConfigChange> listener : listeners) {
                try { listener.accept(event); } catch (RuntimeException e) { log.warn("config listener failed", e); }
            }
            return current;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            listeners.clear();
            Exception failure = null;
            try { if (watch != null) watch.close(); } catch (Exception e) { failure = e; }
            try { source.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw new ConfigException("cannot close config center", failure);
        }

        private void ensureOpen() { if (closed.get()) throw new IllegalStateException("config center is closed"); }
    }
}
