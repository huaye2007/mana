package cn.managame.config;

import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

/** Opens a {@link ConfigCenter} from declared options, or from a source supplied directly. */
public final class ConfigFactory {
    private ConfigFactory() { }

    /**
     * Opens a center over the backend named by {@code options}.
     *
     * <p>Returns only after the first snapshot has loaded and passed validation, so a process never
     * starts on a half-initialized config.</p>
     */
    public static ConfigCenter open(ConfigOptions options) {
        Map<String, ConfigProvider> providers = new LinkedHashMap<>();
        ServiceLoader.load(ConfigProvider.class)
                .forEach(provider -> providers.putIfAbsent(provider.type().toLowerCase(Locale.ROOT), provider));
        ConfigProvider provider = providers.get(options.type());
        if (provider == null) {
            throw new ConfigException("config provider is not available: " + options.type()
                    + " (on the classpath: " + providers.keySet() + ")");
        }
        return open(provider.create(options), options);
    }

    /** Opens a center over a source built by the caller, with the default validation and refresh policy. */
    public static ConfigCenter open(ConfigSource source) {
        return DefaultConfigCenter.open(Objects.requireNonNull(source, "source"), ConfigValidator.none(),
                Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(90));
    }

    /**
     * Opens a center over a source built by the caller, taking validation and refresh policy from
     * {@code options}. The backend settings in {@code options} are ignored.
     *
     * <p>This is the seam for tests and for embedding: pair it with
     * {@link cn.managame.config.source.MemoryConfigSource MemoryConfigSource} to drive config changes
     * from a test without a file, a container or an SPI registration.</p>
     */
    public static ConfigCenter open(ConfigSource source, ConfigOptions options) {
        return DefaultConfigCenter.open(Objects.requireNonNull(source, "source"), options.validator(),
                options.healthCheckInterval(), options.refreshInterval(), options.staleAfter());
    }

    static final class DefaultConfigCenter implements ConfigCenter {
        private static final Logger log = LoggerFactory.getLogger(DefaultConfigCenter.class);
        private final ConfigSource source;
        private final ConfigValidator validator;
        private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>();
        /** Sticky until an update is accepted: a rejected update or a failed watch. */
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();
        /** Cleared by the next successful probe: the backend was unreachable. */
        private final AtomicReference<Throwable> lastProbeError = new AtomicReference<>();
        private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
        private final ExecutorService listenerExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("config-listener-", 0).factory());
        /** Probing and periodic reload; two threads so a slow reload cannot delay a probe. */
        private final ScheduledExecutorService healthExecutor = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("config-health-", 0).factory());
        /** Watch recovery only, so it can never queue behind a health check that is timing out. */
        private final ScheduledExecutorService recoveryExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("config-recovery-", 0).factory());
        private final AtomicBoolean watchRecoveryScheduled = new AtomicBoolean();
        private final AtomicInteger watchRecoveryAttempts = new AtomicInteger();
        private final AtomicLong watchGeneration = new AtomicLong();
        private final AtomicLong updateSequence = new AtomicLong();
        private final AtomicLong lastSuccessfulContactNanos = new AtomicLong(System.nanoTime());
        private final AtomicBoolean watchHealthy = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long healthCheckIntervalMillis;
        private final long refreshIntervalMillis;
        private final long staleAfterNanos;
        private boolean initialized;
        private ConfigData bufferedUpdate;
        private long highestSeenSourceRevision = ConfigData.UNVERSIONED;
        private long highestAcceptedSourceRevision = ConfigData.UNVERSIONED;
        private Map<String, String> highestSeenSourceValues = Map.of();
        private volatile AutoCloseable watch;

        private DefaultConfigCenter(ConfigSource source, ConfigValidator validator,
                                    Duration healthCheckInterval, Duration refreshInterval, Duration staleAfter) {
            this.source = source;
            this.validator = validator;
            healthCheckIntervalMillis = healthCheckInterval.toMillis();
            refreshIntervalMillis = refreshInterval.toMillis();
            staleAfterNanos = staleAfter.isZero() ? 0 : staleAfter.toNanos();
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator) {
            return open(source, validator, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        }

        static ConfigCenter open(ConfigSource source, ConfigValidator validator, Duration healthCheckInterval,
                                 Duration refreshInterval, Duration staleAfter) {
            DefaultConfigCenter center = new DefaultConfigCenter(source, validator,
                    healthCheckInterval, refreshInterval, staleAfter);
            try {
                // Watch first, then load once. Registering the watch up front closes the window where an
                // update published between the read and the registration would be lost, which the previous
                // order had to pay for with a second full load on every startup.
                center.watch = center.startWatch();
                center.initialize();
                center.startMaintenance();
                return center;
            } catch (Exception error) {
                try {
                    center.close();
                } catch (Exception closeError) {
                    error.addSuppressed(closeError);
                }
                throw new ConfigException("cannot open config center", error);
            }
        }

        private void initialize() throws Exception {
            ConfigData initial = source.loadData();
            lastSuccessfulContactNanos.set(System.nanoTime());
            ConfigData buffered;
            synchronized (this) {
                ConfigSnapshot candidate = new ConfigSnapshot(1, Instant.now(), initial.values());
                validator.validate(candidate);
                if (initial.isVersioned()) {
                    highestSeenSourceRevision = initial.revision();
                    highestAcceptedSourceRevision = initial.revision();
                    highestSeenSourceValues = candidate.values();
                }
                snapshot.set(candidate);
                initialized = true;
                buffered = bufferedUpdate;
                bufferedUpdate = null;
                // A versioned source orders itself, so a buffered update can be applied directly.
                if (buffered != null && buffered.isVersioned()) {
                    applyQuietly(buffered);
                    buffered = null;
                }
            }
            // An unversioned source gives no ordering between the load and a concurrent push, so settle
            // it with one more load. Only pays off in the rare case where the race actually happened.
            if (buffered != null) {
                try {
                    loadAndApply();
                } catch (Exception error) {
                    lastError.set(error);
                    log.warn("config reload after startup race failed; retaining initial snapshot", error);
                }
            }
        }

        /** Applies an update whose rejection must not fail the caller, keeping the last good snapshot. */
        private void applyQuietly(ConfigData data) {
            try {
                apply(data);
            } catch (RuntimeException error) {
                lastError.set(error);
                log.warn("config update rejected; retaining last known good snapshot", error);
            }
        }

        @Override public ConfigSnapshot snapshot() { return snapshot.get(); }

        @Override public ConfigSnapshot reload() {
            ensureOpen();
            try {
                return loadAndApply();
            } catch (Exception error) {
                lastError.set(error);
                throw new ConfigException("cannot reload config", error);
            }
        }

        @Override public AutoCloseable listen(Consumer<ConfigChange> listener) { return listen(listener, false); }

        @Override public synchronized AutoCloseable listen(Consumer<ConfigChange> listener, boolean deliverCurrent) {
            ensureOpen();
            ListenerRegistration registration = new ListenerRegistration(
                    Objects.requireNonNull(listener, "listener"));
            listeners.add(registration);
            if (deliverCurrent) {
                // Registering and delivering under the same lock that publishes updates means the listener
                // sees every change from this point on, with no gap and no duplicate.
                ConfigSnapshot current = snapshot.get();
                registration.publish(change(
                        new ConfigSnapshot(current.version(), current.loadedAt(), Map.of()), current));
            }
            return registration;
        }

        private ConfigSnapshot loadAndApply() throws Exception {
            for (int attempt = 0; attempt < 2; attempt++) {
                long observedSequence = updateSequence.get();
                ConfigData data = source.loadData();
                lastSuccessfulContactNanos.set(System.nanoTime());
                lastProbeError.set(null);
                if (data.isVersioned()) return apply(data);
                // Unversioned data cannot be ordered against a push that landed while we were loading,
                // so drop this read if one did and try again rather than moving the snapshot backwards.
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
                // Revision arbitration for sources that order their own publishes (Etcd today).
                if (data.revision() < highestSeenSourceRevision) return snapshot.get();  // stale read, ignore
                if (data.revision() == highestSeenSourceRevision) {
                    // The same revision must always carry the same content; if it does not, the source is
                    // lying about its ordering and silently accepting either version would be worse.
                    if (!highestSeenSourceValues.equals(immutable)) {
                        throw new ConfigException("source returned different values for revision " + data.revision());
                    }
                    // Seen but never accepted means this revision already failed validation. Re-running the
                    // validator on identical content would just re-throw, so leave the snapshot alone.
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
            changed.removeIf(key -> Objects.equals(previous.get(key), current.get(key)));
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
            lastSuccessfulContactNanos.set(System.nanoTime());
            lastProbeError.set(null);
            if (!initialized) {
                // Arrived while the first load was still in flight; initialize() settles the ordering.
                bufferedUpdate = data;
                return;
            }
            applyQuietly(data);
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
                recoveryExecutor.schedule(this::restartWatch, delay, TimeUnit.MILLISECONDS);
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

        private void startMaintenance() {
            if (healthCheckIntervalMillis > 0) {
                healthExecutor.scheduleWithFixedDelay(this::checkHealth,
                        healthCheckIntervalMillis, healthCheckIntervalMillis, TimeUnit.MILLISECONDS);
            }
            if (refreshIntervalMillis > 0) {
                healthExecutor.scheduleWithFixedDelay(this::refresh,
                        refreshIntervalMillis, refreshIntervalMillis, TimeUnit.MILLISECONDS);
            }
        }

        /** Liveness only. Deliberately does not transfer documents, so a large fleet stays cheap. */
        private void checkHealth() {
            if (closed.get()) return;
            try {
                source.ping();
                lastSuccessfulContactNanos.set(System.nanoTime());
                lastProbeError.set(null);
            } catch (Exception error) {
                lastProbeError.set(error);
                log.warn("config health check failed", error);
            }
        }

        /** Safety net for an update no watch reported. Far rarer than the liveness probe. */
        private void refresh() {
            if (closed.get()) return;
            try {
                loadAndApply();
            } catch (Exception error) {
                lastError.set(error);
                log.warn("config periodic refresh failed", error);
            }
        }

        @Override public boolean isHealthy() {
            return !closed.get() && watchHealthy.get()
                    && lastError.get() == null && lastProbeError.get() == null && !isStale();
        }

        @Override public Optional<Throwable> lastError() {
            Throwable error = lastError.get();
            if (error != null) return Optional.of(error);
            Throwable probeError = lastProbeError.get();
            if (probeError != null) return Optional.of(probeError);
            if (isStale()) return Optional.of(new ConfigException("config source health check is stale"));
            return Optional.empty();
        }

        private boolean isStale() {
            return staleAfterNanos > 0
                    && System.nanoTime() - lastSuccessfulContactNanos.get() > staleAfterNanos;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            watchHealthy.set(false);
            watchGeneration.incrementAndGet();
            healthExecutor.shutdownNow();
            recoveryExecutor.shutdownNow();
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
