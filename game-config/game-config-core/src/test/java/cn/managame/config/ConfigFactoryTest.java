package cn.managame.config;

import cn.managame.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            while (source.watchCount < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(source.watchCount >= 2);
            assertTrue(center.isHealthy());
        } finally {
            center.close();
        }
    }

    private static final class FakeSource implements ConfigSource {
        private final Map<String, String> initial;
        private Consumer<Map<String, String>> onUpdate;
        private Consumer<Throwable> onError;
        private volatile int watchCount;
        private boolean closed;

        private FakeSource(Map<String, String> initial) { this.initial = initial; }
        @Override public Map<String, String> load() { return initial; }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            this.onError = onError;
            watchCount++;
            return () -> {
                if (this.onUpdate == onUpdate) this.onUpdate = null;
                if (this.onError == onError) this.onError = null;
            };
        }
        private void emit(Map<String, String> values) { onUpdate.accept(values); }
        private void failWatch(Throwable error) { onError.accept(error); }
        @Override public void close() { closed = true; }
    }
}
