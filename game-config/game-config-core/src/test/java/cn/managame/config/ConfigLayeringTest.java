package cn.managame.config;

import cn.managame.config.source.MemoryConfigSource;
import cn.managame.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLayeringTest {

    @Test
    void laterLayersOverrideEarlierOnes() {
        MemoryConfigSource remote = MemoryConfigSource.named("layering-remote");
        remote.emit(Map.of("game.server.port", "9090", "game.remote.only", "yes"));
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.inline(Map.of("game.server.port", "8080", "game.base.only", "yes")))
                .layer(ConfigLayer.memory("layering-remote"))
                .build())) {
            assertEquals(9090, center.snapshot().getInt("game.server.port", 0));
            assertEquals("yes", center.snapshot().get("game.base.only"));
            assertEquals("yes", center.snapshot().get("game.remote.only"));
        } finally {
            MemoryConfigSource.unregister("layering-remote");
        }
    }

    @Test
    void systemPropertiesLayerOverridesTheBackendUnderneath() {
        System.setProperty("game.layering.port", "7777");
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.inline(Map.of("game.layering.port", "8080")))
                .systemProperties("game.layering.")
                .build())) {
            assertEquals(7777, center.snapshot().getInt("game.layering.port", 0));
        } finally {
            System.clearProperty("game.layering.port");
        }
    }

    @Test
    void requiredKeysFailStartupInsteadOfRunningWithoutThem() {
        ConfigException error = assertThrows(ConfigException.class, () -> ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.inline(Map.of("game.db.url", "jdbc:x")))
                .require("game.db.password")
                .build()));
        assertTrue(rootMessage(error).contains("game.db.password"), rootMessage(error));
    }

    @Test
    void requiredKeysAlsoGuardLaterUpdates() {
        MemoryConfigSource source = new MemoryConfigSource(Map.of("game.db.password", "secret"));
        try (ConfigCenter center = ConfigFactory.open(source, ConfigOptions.builder()
                .require("game.db.password")
                .build())) {
            source.emit(Map.of("game.db.password", ""));
            // A publish that blanks a required key is rejected, keeping the last known good snapshot.
            assertEquals("secret", center.snapshot().get("game.db.password"));
            assertTrue(center.lastError().isPresent());
        }
    }

    @Test
    void unknownLayerTypeNamesWhatIsAvailable() {
        ConfigException error = assertThrows(ConfigException.class, () -> ConfigFactory.open(
                ConfigOptions.builder("does-not-exist").resource("x").build()));
        assertTrue(error.getMessage().contains("does-not-exist"));
        assertTrue(error.getMessage().contains("memory"), error.getMessage());
    }

    @Test
    void reportsWhichLayerWonEachKey() {
        MemoryConfigSource remote = MemoryConfigSource.named("origins-remote");
        remote.emit(Map.of("game.server.port", "9000", "game.mode", "prod"));
        System.setProperty("game.server.port", "9090");
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.builder("memory").name("file")
                        .property("game.server.port", "8080").property("game.name", "mana").build())
                .layer(ConfigLayer.builder("memory").name("remote").property("name", "origins-remote").build())
                .systemProperties("game.server.")
                .build())) {
            assertEquals(9090, center.snapshot().getInt("game.server.port", 0));

            Map<String, String> origins = center.origins();
            assertEquals("system", origins.get("game.server.port"));
            assertEquals("remote", origins.get("game.mode"));
            assertEquals("file", origins.get("game.name"));

            // The full precedence chain for one key, lowest first, so a surprising value is traceable
            // without bisecting the stack.
            assertEquals(List.of(
                            new ConfigOrigin("file", "8080"),
                            new ConfigOrigin("remote", "9000"),
                            new ConfigOrigin("system", "9090")),
                    center.explain("game.server.port"));
            assertEquals(List.of(), center.explain("absent.key"));
        } finally {
            System.clearProperty("game.server.port");
            MemoryConfigSource.unregister("origins-remote");
        }
    }

    @Test
    void repeatedLayerNamesAreDisambiguatedByPosition() {
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.inline(Map.of("a", "1")))
                .layer(ConfigLayer.inline(Map.of("a", "2")))
                .build())) {
            assertEquals("2", center.snapshot().get("a"));
            assertEquals("memory#2", center.origins().get("a"));
            assertEquals(List.of(new ConfigOrigin("memory#1", "1"), new ConfigOrigin("memory#2", "2")),
                    center.explain("a"));
        }
    }

    @Test
    void singleLayerStackStillAttributesItsValues() {
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.builder("memory").name("defaults").property("a", "1").build())
                .build())) {
            assertEquals(Map.of("a", "defaults"), center.origins());
            assertEquals(List.of(new ConfigOrigin("defaults", "1")), center.explain("a"));
        }
    }

    @Test
    void updatingOneLayerRepublishesWithoutReloadingTheOthers() throws Exception {
        CountingSource stable = new CountingSource(Map.of("a", "1"));
        PushableSource changing = new PushableSource(Map.of("b", "1"));
        ConfigSource composite = new CompositeConfigSource(List.of(stable, changing), List.of("stable", "changing"));
        CountDownLatch updated = new CountDownLatch(1);
        try (ConfigCenter center = ConfigFactory.open(composite)) {
            assertEquals(1, stable.loads.get());
            center.listen(change -> { if ("2".equals(change.current().get("b"))) updated.countDown(); });

            changing.emit(Map.of("b", "2"));
            assertTrue(updated.await(1, TimeUnit.SECONDS));
            assertEquals("2", center.snapshot().get("b"));
            // The untouched layer is served from cache: a push on one backend must not fan out into
            // reads against every other backend in the stack.
            assertEquals("1", center.snapshot().get("a"));
            assertEquals(1, stable.loads.get());
        }
    }

    @Test
    void compositeLoadsLayersInParallelAndReportsTheFailingOne() {
        CountingSource healthy = new CountingSource(Map.of("a", "1"));
        FailingSource broken = new FailingSource();
        ConfigSource composite = new CompositeConfigSource(List.of(healthy, broken), List.of("healthy", "broken"));
        ConfigException error = assertThrows(ConfigException.class, () -> ConfigFactory.open(composite));
        assertTrue(rootMessage(error).contains("layer is down"), rootMessage(error));
    }

    @Test
    void memorySourceDrivesUpdatesIntoARunningCenter() throws Exception {
        MemoryConfigSource source = new MemoryConfigSource(Map.of("game.mode", "dev"));
        CountDownLatch updated = new CountDownLatch(1);
        try (ConfigCenter center = ConfigFactory.open(source)) {
            center.listen(change -> { if ("prod".equals(change.current().get("game.mode"))) updated.countDown(); });
            source.emit(Map.of("game.mode", "prod"));
            assertTrue(updated.await(1, TimeUnit.SECONDS));
            assertEquals("prod", center.snapshot().get("game.mode"));
            assertNull(center.snapshot().get("removed.key"));
        }
    }

    private static String rootMessage(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('|');
            for (Throwable suppressed : current.getSuppressed()) text.append(suppressed.getMessage()).append('|');
        }
        return text.toString();
    }

    private static final class CountingSource implements ConfigSource {
        private final Map<String, String> values;
        private final AtomicInteger loads = new AtomicInteger();

        private CountingSource(Map<String, String> values) { this.values = values; }

        @Override public Map<String, String> load() {
            loads.incrementAndGet();
            return values;
        }
        @Override public void ping() { }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            return () -> { };
        }
    }

    private static final class PushableSource implements ConfigSource {
        private volatile Map<String, String> values;
        private volatile Consumer<Map<String, String>> onUpdate;

        private PushableSource(Map<String, String> values) { this.values = values; }

        @Override public Map<String, String> load() { return values; }
        @Override public void ping() { }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            this.onUpdate = onUpdate;
            return () -> this.onUpdate = null;
        }
        private void emit(Map<String, String> next) {
            values = next;
            onUpdate.accept(next);
        }
    }

    private static final class FailingSource implements ConfigSource {
        @Override public Map<String, String> load() { throw new IllegalStateException("layer is down"); }
        @Override public void ping() { throw new IllegalStateException("layer is down"); }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            return () -> { };
        }
    }
}
