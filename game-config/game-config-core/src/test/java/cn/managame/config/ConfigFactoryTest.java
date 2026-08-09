package cn.managame.config;

import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFactoryTest {

    @Test
    void rejectsInvalidUpdateAndRetainsLastKnownGoodSnapshot() {
        FakeSource source = new FakeSource(Map.of("port", "8080"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source,
                candidate -> {
                    if (candidate.getInt("port", 0) <= 0) throw new IllegalArgumentException("invalid port");
                });
        try {
            source.emit(Map.of("port", "0"));
            assertEquals(8080, center.snapshot().getInt("port", 0));
            assertFalse(center.isHealthy());
            assertTrue(center.lastError().isPresent());

            source.emit(Map.of("port", "9090"));
            assertEquals(9090, center.snapshot().getInt("port", 0));
            assertTrue(center.isHealthy());
        } finally {
            center.close();
        }
    }

    @Test
    void listenerRunsOutsideProviderUpdateThread() throws Exception {
        FakeSource source = new FakeSource(Map.of("value", "old"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        center.listen(change -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            source.emit(Map.of("value", "new"));
            assertEquals("new", center.snapshot().get("value"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            center.close();
        }
    }

    @Test
    void invalidInitialSnapshotClosesSource() {
        FakeSource source = new FakeSource(Map.of("port", "0"));
        assertThrows(ConfigException.class, () -> ConfigFactory.DefaultConfigCenter.open(source,
                candidate -> { throw new IllegalArgumentException("invalid"); }));
        assertTrue(source.closed);
    }

    @Test
    void failedWatchIsRestarted() throws Exception {
        FakeSource source = new FakeSource(Map.of("value", "ok"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        try {
            source.failWatch(new IllegalStateException("lost"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((source.watchCount < 2 || !center.isHealthy()) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(source.watchCount >= 2);
            assertTrue(center.isHealthy());
            source.emitFromWatch(0, Map.of("value", "stale"));
            assertEquals("ok", center.snapshot().get("value"));
            source.emit(Map.of("value", "recovered"));
            assertEquals("recovered", center.snapshot().get("value"));
        } finally {
            center.close();
        }
    }

    @Test
    void reloadDoesNotOverwriteNewerWatchUpdate() {
        RacingReloadSource source = new RacingReloadSource();
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        try {
            source.armed = true;
            ConfigSnapshot reloaded = center.reload();
            assertEquals("new", reloaded.get("value"));
            assertEquals("new", center.snapshot().get("value"));
        } finally {
            center.close();
        }
    }

    @Test
    void registersWatchBeforeFirstLoadSoStartupCostsOneRead() {
        ChangeDuringRegistrationSource source = new ChangeDuringRegistrationSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none())) {
            // The watch is live before the read, so the value published during registration is simply
            // what the single load returns. No second load is needed to close the gap.
            assertEquals("new", center.snapshot().get("value"));
            assertEquals(1, source.loadCount);
        }
    }

    @Test
    void settlesUnversionedUpdateThatArrivesDuringTheFirstLoad() {
        PushDuringFirstLoadSource source = new PushDuringFirstLoadSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none())) {
            // An unversioned push cannot be ordered against the in-flight read, so exactly one extra
            // load settles it; the snapshot must end up on the newer value either way.
            assertEquals("new", center.snapshot().get("value"));
            assertEquals(2, source.loadCount);
        }
    }

    @Test
    void retainsValidInitialSnapshotWhenStartupRaceReloadFails() {
        FailingRaceReloadSource source = new FailingRaceReloadSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none())) {
            assertEquals("initial", center.snapshot().get("value"));
            assertFalse(center.isHealthy());
            assertTrue(center.lastError().isPresent());
        }
    }

    @Test
    void retainsLastKnownGoodSnapshotWhenReloadFails() {
        FailingReloadSource source = new FailingReloadSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none())) {
            source.failLoads = true;
            assertThrows(ConfigException.class, center::reload);
            assertEquals("initial", center.snapshot().get("value"));
            assertFalse(center.isHealthy());
            assertTrue(center.lastError().isPresent());
        }
    }

    @Test
    void rejectsLateSourceRevision() {
        VersionedSource source = new VersionedSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none())) {
            source.emit(new ConfigData(12, Map.of("value", "new")));
            source.emit(new ConfigData(11, Map.of("value", "stale")));
            assertEquals("new", center.snapshot().get("value"));
        }
    }

    @Test
    void slowListenerIsIsolatedAndPendingChangesAreCoalesced() throws Exception {
        FakeSource source = new FakeSource(Map.of("value", "0"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowLatest = new CountDownLatch(1);
        CountDownLatch fastLatest = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        AtomicReference<String> fastValue = new AtomicReference<>();
        center.listen(change -> {
            if (slowCalls.incrementAndGet() == 1) {
                slowEntered.countDown();
                try { releaseSlow.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if ("1000".equals(change.current().get("value"))) slowLatest.countDown();
        });
        center.listen(change -> {
            fastValue.set(change.current().get("value"));
            if ("1000".equals(fastValue.get())) fastLatest.countDown();
        });
        try {
            source.emit(Map.of("value", "1"));
            assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
            for (int value = 2; value <= 1000; value++) source.emit(Map.of("value", Integer.toString(value)));
            assertTrue(fastLatest.await(1, TimeUnit.SECONDS));
            assertEquals("1000", fastValue.get());
            releaseSlow.countDown();
            assertTrue(slowLatest.await(1, TimeUnit.SECONDS));
            assertEquals(2, slowCalls.get());
        } finally {
            releaseSlow.countDown();
            center.close();
        }
    }

    @Test
    void activeHealthCheckDetectsAndClearsConnectivityFailure() throws Exception {
        HealthSource source = new HealthSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none(),
                Duration.ofMillis(20), Duration.ZERO, Duration.ofMillis(100))) {
            source.failLoads = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (center.isHealthy() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(center.isHealthy());
            assertTrue(center.lastError().isPresent());

            source.failLoads = false;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!center.isHealthy() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(center.isHealthy());
        }
    }

    @Test
    void staleThresholdMarksSourceUnhealthyWithoutRecentContact() throws Exception {
        HealthSource source = new HealthSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none(),
                Duration.ZERO, Duration.ZERO, Duration.ofMillis(30))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (center.isHealthy() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(center.isHealthy());
            assertTrue(center.lastError().orElseThrow().getMessage().contains("stale"));
        }
    }

    @Test
    void healthChecksProbeWithoutReadingDocuments() throws Exception {
        ProbeSource source = new ProbeSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none(),
                Duration.ofMillis(10), Duration.ZERO, Duration.ZERO)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.pings.get() < 3 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(source.pings.get() >= 3, "expected repeated probes");
            // Liveness must not pull documents; that is what makes a large fleet affordable.
            assertEquals(1, source.loads.get());
            assertTrue(center.isHealthy());
        }
    }

    @Test
    void periodicRefreshPicksUpAnUpdateNoWatchReported() throws Exception {
        ProbeSource source = new ProbeSource();
        try (ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none(),
                Duration.ZERO, Duration.ofMillis(10), Duration.ZERO)) {
            source.current = Map.of("value", "silently-changed");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"silently-changed".equals(center.snapshot().get("value")) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals("silently-changed", center.snapshot().get("value"));
        }
    }

    @Test
    void listenDeliversCurrentSnapshotWithoutAGapForConcurrentUpdates() throws Exception {
        FakeSource source = new FakeSource(Map.of("value", "initial"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        List<ConfigChange> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch updated = new CountDownLatch(1);
        try {
            center.listen(change -> {
                observed.add(change);
                if ("initial".equals(change.current().get("value"))) initial.countDown();
                if ("later".equals(change.current().get("value"))) updated.countDown();
            }, true);
            assertTrue(initial.await(1, TimeUnit.SECONDS));
            ConfigChange first = observed.getFirst();
            // The synthetic first event reports every key as changed, so one code path can handle
            // both the initial state and later updates.
            assertEquals(Set.of("value"), first.changedKeys());
            assertTrue(first.previous().values().isEmpty());
            assertEquals("initial", first.current().get("value"));

            source.emit(Map.of("value", "later"));
            assertTrue(updated.await(1, TimeUnit.SECONDS));
        } finally {
            center.close();
        }
    }

    @Test
    void refCachesDerivedValueUntilTheSnapshotChanges() {
        FakeSource source = new FakeSource(Map.of("port", "8080"));
        ConfigCenter center = ConfigFactory.DefaultConfigCenter.open(source, ConfigValidator.none());
        AtomicInteger parses = new AtomicInteger();
        try {
            ConfigRef<Integer> port = center.ref(snapshot -> {
                parses.incrementAndGet();
                return snapshot.getInt("port", 0);
            });
            for (int i = 0; i < 100; i++) assertEquals(8080, port.get());
            assertEquals(1, parses.get(), "derived value must be computed once per snapshot");

            source.emit(Map.of("port", "9090"));
            assertEquals(9090, port.get());
            assertEquals(2, parses.get());
        } finally {
            center.close();
        }
    }

    private static final class ProbeSource implements ConfigSource {
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger pings = new AtomicInteger();
        private volatile Map<String, String> current = Map.of("value", "ok");

        @Override public Map<String, String> load() {
            loads.incrementAndGet();
            return current;
        }
        @Override public void ping() { pings.incrementAndGet(); }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return () -> { };
        }
    }

    private static final class FakeSource implements ConfigSource {
        private final Map<String, String> initial;
        private Consumer<Map<String, String>> onUpdate;
        private Consumer<Throwable> onError;
        private final List<Consumer<Map<String, String>>> updates = new ArrayList<>();
        private volatile int watchCount;
        private boolean closed;

        private FakeSource(Map<String, String> initial) { this.initial = initial; }
        @Override public Map<String, String> load() { return initial; }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            this.onError = onError;
            updates.add(onUpdate);
            watchCount++;
            return () -> {
                this.onUpdate = null;
                this.onError = null;
            };
        }
        private void emit(Map<String, String> values) { onUpdate.accept(values); }
        private void emitFromWatch(int index, Map<String, String> values) { updates.get(index).accept(values); }
        private void failWatch(Throwable error) { onError.accept(error); }
        @Override public void close() { closed = true; }
    }

    /** Publishes a newer value through the watch from inside a load, so the read returns stale data. */
    private static final class RacingReloadSource implements ConfigSource {
        private Consumer<Map<String, String>> onUpdate;
        private Map<String, String> current = Map.of("value", "old");
        private volatile boolean armed;

        @Override public Map<String, String> load() {
            if (armed) {
                armed = false;
                Map<String, String> stale = current;
                current = Map.of("value", "new");
                onUpdate.accept(current);
                return stale;
            }
            return current;
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            return () -> this.onUpdate = null;
        }
    }

    private static final class ChangeDuringRegistrationSource implements ConfigSource {
        private Map<String, String> current = Map.of("value", "old");
        private int loadCount;

        @Override public Map<String, String> load() {
            loadCount++;
            return current;
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            current = Map.of("value", "new");
            return () -> { };
        }
    }

    /** Pushes through the watch while the very first load is still in flight. */
    private static final class PushDuringFirstLoadSource implements ConfigSource {
        private Consumer<Map<String, String>> onUpdate;
        private Map<String, String> current = Map.of("value", "old");
        private int loadCount;

        @Override public Map<String, String> load() {
            if (++loadCount == 1) {
                Map<String, String> stale = current;
                current = Map.of("value", "new");
                onUpdate.accept(current);
                return stale;
            }
            return current;
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            return () -> this.onUpdate = null;
        }
    }

    /** Races the first load, then fails the reload that the race forces. */
    private static final class FailingRaceReloadSource implements ConfigSource {
        private Consumer<Map<String, String>> onUpdate;
        private int loadCount;

        @Override public Map<String, String> load() {
            if (++loadCount == 1) {
                onUpdate.accept(Map.of("value", "pushed"));
                return Map.of("value", "initial");
            }
            throw new IllegalStateException("temporarily unavailable");
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            return () -> this.onUpdate = null;
        }
    }

    private static final class FailingReloadSource implements ConfigSource {
        private volatile boolean failLoads;

        @Override public Map<String, String> load() {
            if (failLoads) throw new IllegalStateException("temporarily unavailable");
            return Map.of("value", "initial");
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return () -> { };
        }
    }

    private static final class VersionedSource implements ConfigSource {
        private ConfigData current = new ConfigData(10, Map.of("value", "old"));
        private Consumer<ConfigData> onUpdate;

        @Override public Map<String, String> load() { return current.values(); }
        @Override public ConfigData loadData() { return current; }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return () -> { };
        }
        @Override public AutoCloseable watchData(Consumer<ConfigData> onUpdate,
                                                 Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            return () -> this.onUpdate = null;
        }
        private void emit(ConfigData data) {
            current = data;
            onUpdate.accept(data);
        }
    }

    private static final class HealthSource implements ConfigSource {
        private volatile boolean failLoads;

        @Override public Map<String, String> load() {
            if (failLoads) throw new IllegalStateException("unreachable");
            return Map.of("value", "ok");
        }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return () -> { };
        }
    }
}
