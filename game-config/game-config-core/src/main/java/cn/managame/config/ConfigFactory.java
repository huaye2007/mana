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
import java.util.concurrent.atomic.AtomicLong;
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
        private final AtomicLong watchGeneration = new AtomicLong();
        private final AtomicLong updateSequence = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile AutoCloseable watch;

        private DefaultConfigCenter(ConfigSource source, ConfigValidator validator, ConfigSnapshot initial) {
            this.source = source;
            this.validator = validator;
            snapshot = new AtomicReference<>(initial);
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator) {
            DefaultConfigCenter center = null;
            try {
                ConfigSnapshot initial = new ConfigSnapshot(1, Instant.now(), Map.copyOf(source.load()));
                validator.validate(initial);
                center = new DefaultConfigCenter(source, validator, initial);
                center.watch = center.startWatch();
                center.loadAndApply();
                return center;
            } catch (Exception e) {
                try {
                    if (center == null) source.close();
                    else center.close();
                } catch (Exception closeError) {
                    e.addSuppressed(closeError);
                }
                throw new ConfigException("cannot open config center", e);
            }
        }

        @Override public ConfigSnapshot snapshot() { ensureOpen(); return snapshot.get(); }

        @Override public ConfigSnapshot reload() {
            ensureOpen();
            try {
                return loadAndApply();
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

        private ConfigSnapshot loadAndApply() throws Exception {
            for (int attempt = 0; attempt < 2; attempt++) {
                long observedSequence = updateSequence.get();
                Map<String, String> values = source.load();
                ConfigSnapshot applied = applyLoaded(observedSequence, values);
                if (applied != null) return applied;
            }
            return snapshot.get();
        }

        private synchronized ConfigSnapshot applyLoaded(long observedSequence, Map<String, String> values) {
            if (observedSequence != updateSequence.get()) return null;
            return apply(values);
        }

        private synchronized ConfigSnapshot apply(Map<String, String> values) {
            if (closed.get()) return snapshot.get();
            updateSequence.incrementAndGet();
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

        private AutoCloseable startWatch() throws Exception {
            long generation = watchGeneration.incrementAndGet();
            return source.watch(values -> acceptUpdate(generation, values),
                    error -> watchFailed(generation, error));
        }

        private synchronized void acceptUpdate(long generation, Map<String, String> values) {
            if (closed.get() || generation != watchGeneration.get()) return;
            try {
                apply(values);
            } catch (RuntimeException error) {
                lastError.set(error);
                log.warn("config update rejected; retaining last known good snapshot", error);
            }
        }

        private synchronized void watchFailed(long generation, Throwable error) {
            if (closed.get() || generation != watchGeneration.get()) return;
            updateSequence.incrementAndGet();
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
                AutoCloseable previous;
                synchronized (this) {
                    if (closed.get()) return;
                    previous = watch;
                    watch = null;
                    watchGeneration.incrementAndGet();
                }
                if (previous != null) previous.close();
                if (closed.get()) return;
                replacement = startWatch();
                synchronized (this) {
                    if (closed.get()) {
                        replacement.close();
                        return;
                    }
                    watch = replacement;
                }
                try {
                    loadAndApply();
                } catch (RuntimeException error) {
                    lastError.set(error);
                    log.warn("config update rejected after watch recovery; retaining last known good snapshot", error);
                }
                installed = true;
                watchRecoveryAttempts.set(0);
                log.info("config watch recovered");
            } catch (Exception error) {
                if (replacement != null) {
                    try { replacement.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
                    synchronized (this) {
                        if (watch == replacement) watch = null;
                    }
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
            watchGeneration.incrementAndGet();
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
