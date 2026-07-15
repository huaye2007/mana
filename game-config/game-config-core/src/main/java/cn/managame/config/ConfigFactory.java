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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        return DefaultConfigCenter.open(provider.create(options), options.validator());
    }

    static final class DefaultConfigCenter implements ConfigCenter {
        private static final Logger log = LoggerFactory.getLogger(DefaultConfigCenter.class);
        private final ConfigSource source;
        private final ConfigValidator validator;
        private final AtomicReference<ConfigSnapshot> snapshot;
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();
        private final CopyOnWriteArrayList<Consumer<ConfigChange>> listeners = new CopyOnWriteArrayList<>();
        private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("config-listener-", 0).factory());
        private final ScheduledExecutorService watchRecoveryExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("config-watch-recovery-", 0).factory());
        private final AtomicBoolean watchRecoveryScheduled = new AtomicBoolean();
        private final AtomicInteger watchRecoveryAttempts = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile AutoCloseable watch;

        private DefaultConfigCenter(ConfigSource source, ConfigValidator validator, ConfigSnapshot initial) {
            this.source = source;
            this.validator = validator;
            snapshot = new AtomicReference<>(initial);
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator) {
            try {
                ConfigSnapshot initial = new ConfigSnapshot(1, Instant.now(), Map.copyOf(source.load()));
                validator.validate(initial);
                DefaultConfigCenter center = new DefaultConfigCenter(source, validator, initial);
                center.watch = source.watch(center::acceptUpdate, center::watchFailed);
                return center;
            } catch (Exception e) {
                try { source.close(); } catch (Exception closeError) { e.addSuppressed(closeError); }
                throw new ConfigException("cannot open config center", e);
            }
        }

        @Override public ConfigSnapshot snapshot() { ensureOpen(); return snapshot.get(); }

        @Override public ConfigSnapshot reload() {
            ensureOpen();
            try {
                ConfigSnapshot result = apply(source.load());
                lastError.set(null);
                return result;
            } catch (Exception e) {
                lastError.set(e);
                throw new ConfigException("cannot reload config", e);
            }
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
            if (previous.values().equals(immutable)) {
                lastError.set(null);
                return previous;
            }
            ConfigSnapshot current = new ConfigSnapshot(previous.version() + 1, Instant.now(), immutable);
            validator.validate(current);
            snapshot.set(current);
            lastError.set(null);
            Set<String> changed = new HashSet<>(previous.values().keySet());
            changed.addAll(current.values().keySet());
            changed.removeIf(key -> java.util.Objects.equals(previous.get(key), current.get(key)));
            ConfigChange event = new ConfigChange(previous, current, changed);
            try {
                listenerExecutor.execute(() -> notifyListeners(event));
            } catch (RejectedExecutionException e) {
                if (!closed.get()) {
                    log.warn("config listener dispatch rejected", e);
                }
            }
            return current;
        }

        private void acceptUpdate(Map<String, String> values) {
            try {
                apply(values);
            } catch (RuntimeException error) {
                lastError.set(error);
                log.warn("config update rejected; retaining last known good snapshot", error);
            }
        }

        private void watchFailed(Throwable error) {
            lastError.set(error);
            log.warn("config watch failed", error);
            scheduleWatchRecovery();
        }

        private void scheduleWatchRecovery() {
            if (closed.get() || !watchRecoveryScheduled.compareAndSet(false, true)) return;
            int attempt = watchRecoveryAttempts.getAndIncrement();
            long delay = Math.min(5000L, 100L << Math.min(attempt, 5));
            try {
                watchRecoveryExecutor.schedule(this::restartWatch, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                watchRecoveryScheduled.set(false);
            }
        }

        private void restartWatch() {
            AutoCloseable replacement = null;
            boolean installed = false;
            try {
                replacement = source.watch(this::acceptUpdate, this::watchFailed);
                Map<String, String> latest = source.load();
                AutoCloseable previous;
                synchronized (this) {
                    if (closed.get()) {
                        replacement.close();
                        return;
                    }
                    previous = watch;
                    watch = replacement;
                    installed = true;
                }
                if (previous != null) {
                    try { previous.close(); }
                    catch (Exception closeError) { log.debug("failed to close obsolete config watch", closeError); }
                }
                acceptUpdate(latest);
                watchRecoveryAttempts.set(0);
                log.info("config watch recovered");
            } catch (Exception error) {
                if (replacement != null) {
                    try { replacement.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
                }
                lastError.set(error);
                log.warn("config watch recovery failed", error);
            } finally {
                watchRecoveryScheduled.set(false);
                if (!closed.get() && !installed) scheduleWatchRecovery();
            }
        }

        private void notifyListeners(ConfigChange event) {
            for (Consumer<ConfigChange> listener : listeners) {
                try { listener.accept(event); } catch (RuntimeException e) { log.warn("config listener failed", e); }
            }
        }

        @Override public boolean isHealthy() { return !closed.get() && lastError.get() == null; }

        @Override public java.util.Optional<Throwable> lastError() {
            return java.util.Optional.ofNullable(lastError.get());
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            watchRecoveryExecutor.shutdownNow();
            listeners.clear();
            Exception failure = null;
            try { if (watch != null) watch.close(); } catch (Exception e) { failure = e; }
            try { source.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            listenerExecutor.shutdown();
            try {
                if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) listenerExecutor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listenerExecutor.shutdownNow();
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (failure != null) throw new ConfigException("cannot close config center", failure);
        }

        private void ensureOpen() { if (closed.get()) throw new IllegalStateException("config center is closed"); }
    }
}
