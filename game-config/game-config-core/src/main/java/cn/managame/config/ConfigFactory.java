package cn.managame.config;

import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
        return DefaultConfigCenter.open(provider.create(options), options.validator(),
                options.healthCheckInterval(), options.staleAfter());
    }

    static final class DefaultConfigCenter implements ConfigCenter {
        private static final Logger log = LoggerFactory.getLogger(DefaultConfigCenter.class);
        private final ConfigSource source;
        private final ConfigValidator validator;
        private final AtomicReference<ConfigSnapshot> snapshot;
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();
        private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
        private final ExecutorService listenerExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("config-listener-", 0).factory());
        private final ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("config-maintenance-", 0).factory());
        private final AtomicBoolean watchRecoveryScheduled = new AtomicBoolean();
        private final AtomicInteger watchRecoveryAttempts = new AtomicInteger();
        private final AtomicLong watchGeneration = new AtomicLong();
        private final AtomicLong updateSequence = new AtomicLong();
        private final AtomicLong lastSuccessfulContactNanos = new AtomicLong(System.nanoTime());
        private final AtomicBoolean watchHealthy = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long healthCheckIntervalMillis;
        private final long staleAfterNanos;
        private long highestSeenSourceRevision = ConfigData.UNVERSIONED;
        private long highestAcceptedSourceRevision = ConfigData.UNVERSIONED;
        private Map<String, String> highestSeenSourceValues = Map.of();
        private volatile AutoCloseable watch;

        private DefaultConfigCenter(ConfigSource source, ConfigValidator validator, ConfigData initial,
                                    Duration healthCheckInterval, Duration staleAfter) {
            this.source = source;
            this.validator = validator;
            healthCheckIntervalMillis = healthCheckInterval.toMillis();
            staleAfterNanos = staleAfter.isZero() ? 0 : staleAfter.toNanos();
            if (initial.isVersioned()) {
                highestSeenSourceRevision = initial.revision();
                highestAcceptedSourceRevision = initial.revision();
                highestSeenSourceValues = initial.values();
            }
            snapshot = new AtomicReference<>(new ConfigSnapshot(1, Instant.now(), initial.values()));
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator) {
            return open(source, validator, Duration.ZERO, Duration.ZERO);
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator,
                                 Duration healthCheckInterval, Duration staleAfter) {
            DefaultConfigCenter center = null;
            try {
                ConfigData initial = source.loadData();
                validator.validate(new ConfigSnapshot(1, Instant.now(), initial.values()));
                center = new DefaultConfigCenter(source, validator, initial, healthCheckInterval, staleAfter);
                center.watch = center.startWatch();
                try {
                    center.loadAndApply();
                } catch (Exception error) {
                    center.lastError.set(error);
                    log.warn("config reload after watch registration failed; retaining initial snapshot", error);
                }
                center.startHealthChecks();
                return center;
            } catch (Exception error) {
                try {
                    if (center == null) source.close();
                    else center.close();
                } catch (Exception closeError) {
                    error.addSuppressed(closeError);
                }
                throw new ConfigException("cannot open config center", error);
            }
        }

        @Override public ConfigSnapshot snapshot() { ensureOpen(); return snapshot.get(); }

        @Override public ConfigSnapshot reload() {
            ensureOpen();
            try {
                return loadAndApply();
            } catch (Exception error) {
                lastError.set(error);
                throw new ConfigException("cannot reload config", error);
            }
        }

        @Override public AutoCloseable listen(Consumer<ConfigChange> listener) {
            ensureOpen();
            ListenerRegistration registration = new ListenerRegistration(
                    java.util.Objects.requireNonNull(listener, "listener"));
            listeners.add(registration);
            return registration;
        }

        private ConfigSnapshot loadAndApply() throws Exception {
            for (int attempt = 0; attempt < 2; attempt++) {
                long observedSequence = updateSequence.get();
                ConfigData data = source.loadData();
                lastSuccessfulContactNanos.set(System.nanoTime());
                if (data.isVersioned()) return apply(data);
                ConfigSnapshot applied = applyLoaded(observedSequence, data);
                if (applied != null) return applied;
            }
            return snapshot.get();
        }

        private synchronized ConfigSnapshot applyLoaded(long observedSequence, ConfigData data) {
            if (observedSequence != updateSequence.get()) return null;
            return apply(data);
        }

        private synchronized ConfigSnapshot apply(ConfigData data) {
            if (closed.get()) return snapshot.get();
            updateSequence.incrementAndGet();
            Map<String, String> immutable = data.values();
            if (data.isVersioned()) {
                if (data.revision() < highestSeenSourceRevision) return snapshot.get();
                if (data.revision() == highestSeenSourceRevision) {
                    if (!highestSeenSourceValues.equals(immutable)) {
                        throw new ConfigException("source returned different values for revision " + data.revision());
                    }
                    if (data.revision() > highestAcceptedSourceRevision) return snapshot.get();
                } else {
                    highestSeenSourceRevision = data.revision();
                    highestSeenSourceValues = immutable;
                }
            }
            ConfigSnapshot previous = snapshot.get();
            if (previous.values().equals(immutable)) {
                if (data.isVersioned()) highestAcceptedSourceRevision = data.revision();
                if (watchHealthy.get()) lastError.set(null);
                return previous;
            }
            ConfigSnapshot current = new ConfigSnapshot(previous.version() + 1, Instant.now(), immutable);
            validator.validate(current);
            snapshot.set(current);
            if (data.isVersioned()) highestAcceptedSourceRevision = data.revision();
            if (watchHealthy.get()) lastError.set(null);
            ConfigChange event = change(previous, current);
            listeners.forEach(listener -> listener.publish(event));
            return current;
        }

        private static ConfigChange change(ConfigSnapshot previous, ConfigSnapshot current) {
            Set<String> changed = new HashSet<>(previous.values().keySet());
            changed.addAll(current.values().keySet());
            changed.removeIf(key -> java.util.Objects.equals(previous.get(key), current.get(key)));
            return new ConfigChange(previous, current, changed);
        }

        private AutoCloseable startWatch() throws Exception {
            long generation = watchGeneration.incrementAndGet();
            watchHealthy.set(true);
            try {
                return source.watchData(data -> acceptUpdate(generation, data),
                        error -> watchFailed(generation, error));
            } catch (Exception | Error error) {
                watchHealthy.set(false);
                throw error;
            }
        }

        private synchronized void acceptUpdate(long generation, ConfigData data) {
            if (closed.get() || generation != watchGeneration.get()) return;
            try {
                lastSuccessfulContactNanos.set(System.nanoTime());
                apply(data);
            } catch (RuntimeException error) {
                lastError.set(error);
                log.warn("config update rejected; retaining last known good snapshot", error);
            }
        }

        private synchronized void watchFailed(long generation, Throwable error) {
            if (closed.get() || generation != watchGeneration.get()) return;
            updateSequence.incrementAndGet();
            watchHealthy.set(false);
            lastError.set(error);
            log.warn("config watch failed", error);
            scheduleWatchRecovery();
        }

        private void scheduleWatchRecovery() {
            if (closed.get() || !watchRecoveryScheduled.compareAndSet(false, true)) return;
            int attempt = watchRecoveryAttempts.getAndIncrement();
            long delay = Math.min(5000L, 100L << Math.min(attempt, 5));
            try {
                maintenanceExecutor.schedule(this::restartWatch, delay, TimeUnit.MILLISECONDS);
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
                    watchHealthy.set(false);
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
                watchHealthy.set(false);
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

        private void startHealthChecks() {
            if (healthCheckIntervalMillis == 0) return;
            maintenanceExecutor.scheduleWithFixedDelay(this::checkHealth,
                    healthCheckIntervalMillis, healthCheckIntervalMillis, TimeUnit.MILLISECONDS);
        }

        private void checkHealth() {
            if (closed.get()) return;
            try {
                loadAndApply();
            } catch (Exception error) {
                lastError.set(error);
                log.warn("config health check failed", error);
            }
        }

        @Override public boolean isHealthy() {
            return !closed.get() && watchHealthy.get() && lastError.get() == null && !isStale();
        }

        @Override public java.util.Optional<Throwable> lastError() {
            Throwable error = lastError.get();
            if (error != null) return java.util.Optional.of(error);
            if (isStale()) return java.util.Optional.of(new ConfigException("config source health check is stale"));
            return java.util.Optional.empty();
        }

        private boolean isStale() {
            return staleAfterNanos > 0
                    && System.nanoTime() - lastSuccessfulContactNanos.get() > staleAfterNanos;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            watchHealthy.set(false);
            watchGeneration.incrementAndGet();
            maintenanceExecutor.shutdownNow();
            listeners.forEach(ListenerRegistration::deactivate);
            listeners.clear();
            Exception failure = null;
            try { if (watch != null) watch.close(); } catch (Exception error) { failure = error; }
            try { source.close(); }
            catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            listenerExecutor.shutdown();
            try {
                if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) listenerExecutor.shutdownNow();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                listenerExecutor.shutdownNow();
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw new ConfigException("cannot close config center", failure);
        }

        private void ensureOpen() { if (closed.get()) throw new IllegalStateException("config center is closed"); }

        private final class ListenerRegistration implements AutoCloseable {
            private final Consumer<ConfigChange> listener;
            private final AtomicReference<ConfigChange> pending = new AtomicReference<>();
            private final AtomicBoolean draining = new AtomicBoolean();
            private final AtomicBoolean active = new AtomicBoolean(true);

            private ListenerRegistration(Consumer<ConfigChange> listener) {
                this.listener = listener;
            }

            private void publish(ConfigChange event) {
                if (!active.get()) return;
                pending.accumulateAndGet(event, (queued, incoming) -> queued == null ? incoming
                        : change(queued.previous(), incoming.current()));
                schedule();
            }

            private void schedule() {
                if (!active.get() || !draining.compareAndSet(false, true)) return;
                try {
                    listenerExecutor.execute(this::drain);
                } catch (RejectedExecutionException error) {
                    draining.set(false);
                    if (!closed.get()) log.warn("config listener dispatch rejected", error);
                }
            }

            private void drain() {
                try {
                    while (active.get()) {
                        ConfigChange event = pending.getAndSet(null);
                        if (event == null) return;
                        try { listener.accept(event); }
                        catch (RuntimeException error) { log.warn("config listener failed", error); }
                    }
                } finally {
                    draining.set(false);
                    if (active.get() && pending.get() != null) schedule();
                }
            }

            private void deactivate() {
                active.set(false);
                pending.set(null);
            }

            @Override public void close() {
                deactivate();
                listeners.remove(this);
            }
        }
    }
}
