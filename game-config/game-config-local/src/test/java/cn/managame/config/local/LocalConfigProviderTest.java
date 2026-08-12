package cn.managame.config.local;

import cn.managame.config.ConfigCenter;
import cn.managame.config.ConfigException;
import cn.managame.config.ConfigFactory;
import cn.managame.config.ConfigOptions;
import cn.managame.config.support.JsonConfigFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalConfigProviderTest {
    @TempDir Path directory;

    @Test void mergesFilesAndWatchesChanges() throws Exception {
        Path base = directory.resolve("base.properties");
        Path override = directory.resolve("override.properties");
        Files.writeString(base, "port=7000\nname=base\n");
        Files.writeString(override, "name=override\n");
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(base.toString()).resource(override.toString()).build())) {
            assertEquals(7000, center.snapshot().getInt("port", 0));
            assertEquals("override", center.snapshot().get("name"));
            CountDownLatch changed = new CountDownLatch(1);
            center.listen(event -> changed.countDown());
            Files.writeString(override, "name=latest\n");
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals("latest", center.snapshot().get("name"));
        }
    }

    @Test void treatsRevisionNamedPropertiesAsOrdinaryUnversionedData() throws Exception {
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "_revision=application-value\nname=mana\n");
        var source = new LocalConfigProvider.LocalSource(ConfigOptions.builder("local")
                .resource(file.toString()).build());

        assertFalse(source.loadData().isVersioned());
        assertEquals("application-value", source.load().get("_revision"));
    }

    @Test void canIgnoreMissingOptionalFile() {
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(directory.resolve("missing.properties").toString()).property("required", "false").build())) {
            assertTrue(center.snapshot().values().isEmpty());
        }
    }

    @Test void rejectsInvalidRequiredOption() {
        assertThrows(IllegalArgumentException.class, () -> new LocalConfigProvider.LocalSource(
                ConfigOptions.builder("local").resource(directory.resolve("missing.properties").toString())
                        .property("required", "ture").build()));
    }

    @Test void watchesOptionalFileWhoseParentDoesNotExistYet() throws Exception {
        Path file = directory.resolve("created-later").resolve("application.properties");
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(file.toString()).property("required", "false").build())) {
            CountDownLatch changed = new CountDownLatch(1);
            center.listen(event -> changed.countDown());

            Files.createDirectories(file.getParent());
            Files.writeString(file, "name=latest\n");

            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals("latest", center.snapshot().get("name"));
        }
    }

    @Test void recoversAfterWatchedDirectoryIsReplaced() throws Exception {
        Path configDirectory = directory.resolve("config");
        Files.createDirectories(configDirectory);
        Path file = configDirectory.resolve("application.properties");
        Files.writeString(file, "name=old\n");

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(file.toString()).build())) {
            CountDownLatch changed = new CountDownLatch(1);
            center.listen(event -> changed.countDown());

            Files.move(configDirectory, directory.resolve("config-old"));
            Files.createDirectories(configDirectory);
            Files.writeString(file, "name=new\n");

            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals("new", center.snapshot().get("name"));
        }
    }

    @Test void loadsAndFlattensJsonFile() throws Exception {
        Path json = directory.resolve("application.json");
        Files.writeString(json, """
                {
                  "game": {
                    "server": {"port": 7000, "enabled": true},
                    "name": "mana"
                  },
                  "regions": ["cn", "us"],
                  "servers": [{"host": "127.0.0.1", "ports": [8080, 8081]}],
                  "nullable": null
                }
                """);

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(json.toString()).build())) {
            assertEquals(7000, center.snapshot().getInt("game.server.port", 0));
            assertTrue(center.snapshot().getBoolean("game.server.enabled", false));
            assertEquals("mana", center.snapshot().get("game.name"));
            assertEquals("[\"cn\",\"us\"]", center.snapshot().get("regions"));
            assertEquals("cn", center.snapshot().get("regions[0]"));
            assertEquals("us", center.snapshot().get("regions[1]"));
            assertEquals("127.0.0.1", center.snapshot().get("servers[0].host"));
            assertEquals("[8080,8081]", center.snapshot().get("servers[0].ports"));
            assertEquals(8081, center.snapshot().getInt("servers[0].ports[1]", 0));
            assertEquals("null", center.snapshot().get("nullable"));

            CountDownLatch changed = new CountDownLatch(1);
            center.listen(event -> changed.countDown());
            Files.writeString(json, "{\"game\":{\"server\":{\"port\":8000}}}");
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals(8000, center.snapshot().getInt("game.server.port", 0));
        }
    }

    @Test void laterJsonFileOverridesPropertiesFile() throws Exception {
        Path properties = directory.resolve("base.properties");
        Path json = directory.resolve("override.json");
        Files.writeString(properties, "game.server.port=7000\nname=base\n");
        Files.writeString(json, "{\"game\":{\"server\":{\"port\":8000}}}");

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(properties.toString()).resource(json.toString()).build())) {
            assertEquals(8000, center.snapshot().getInt("game.server.port", 0));
            assertEquals("base", center.snapshot().get("name"));
        }
    }

    @Test void laterArrayRemovesStaleIndexes() throws Exception {
        Path base = directory.resolve("base.json");
        Path override = directory.resolve("override.json");
        Files.writeString(base, "{\"regions\":[\"cn\",\"us\"]}");
        Files.writeString(override, "{\"regions\":[\"eu\"]}");

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(base.toString()).resource(override.toString()).build())) {
            assertEquals("[\"eu\"]", center.snapshot().get("regions"));
            assertEquals("eu", center.snapshot().get("regions[0]"));
            assertNull(center.snapshot().get("regions[1]"));
        }
    }

    @Test void rejectsJsonWithNonObjectRoot() {
        assertThrows(ConfigException.class, () -> new JsonConfigFormat().parse("[1, 2, 3]"));
    }

    @Test void loadsAndWatchesYamlFile() throws Exception {
        Path yaml = directory.resolve("application.yml");
        Files.writeString(yaml, """
                game:
                  server:
                    port: 7000
                    enabled: true
                  name: mana
                regions:
                  - cn
                  - us
                """);

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(yaml.toString()).build())) {
            assertEquals(7000, center.snapshot().getInt("game.server.port", 0));
            assertTrue(center.snapshot().getBoolean("game.server.enabled", false));
            assertEquals("mana", center.snapshot().get("game.name"));
            assertEquals(java.util.List.of("cn", "us"), center.snapshot().getList("regions"));

            CountDownLatch changed = new CountDownLatch(1);
            center.listen(event -> changed.countDown());
            Files.writeString(yaml, "game:\n  server:\n    port: 8000\n");
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals(8000, center.snapshot().getInt("game.server.port", 0));
        }
    }

    @Test void yamlAndPropertiesMixInOneLayer() throws Exception {
        Path base = directory.resolve("base.yml");
        Path override = directory.resolve("override.properties");
        Files.writeString(base, "game:\n  server:\n    port: 7000\n  name: mana\n");
        Files.writeString(override, "game.server.port=8000\n");

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(base.toString()).resource(override.toString()).build())) {
            assertEquals(8000, center.snapshot().getInt("game.server.port", 0));
            assertEquals("mana", center.snapshot().get("game.name"));
        }
    }

    @Test void readsAFileTypeRegisteredFromOutsideTheProvider() throws Exception {
        Path env = directory.resolve("application.env");
        Files.writeString(env, "# comment\nGAME_PORT=9300\nGAME_NAME = mana \n");
        Path properties = directory.resolve("base.properties");
        Files.writeString(properties, "GAME_PORT=7000\nother=kept\n");

        // DotenvConfigFormat lives only in test sources; the provider picks it up by extension and
        // mixes it with properties in one layer.
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(properties.toString()).resource(env.toString()).build())) {
            assertEquals(9300, center.snapshot().getInt("GAME_PORT", 0));
            assertEquals("mana", center.snapshot().get("GAME_NAME"));
            assertEquals("kept", center.snapshot().get("other"));
        }
    }

    @Test void unknownPinnedFormatFailsWithTheAvailableOnes() {
        ConfigException error = assertThrows(ConfigException.class, () -> new LocalConfigProvider.LocalSource(
                ConfigOptions.builder("local").resource(directory.resolve("a.properties").toString())
                        .property("format", "toml").build()));
        assertTrue(error.getMessage().contains("toml"), error.getMessage());
        assertTrue(error.getMessage().contains("json"), error.getMessage());
        assertTrue(error.getMessage().contains("yaml"), error.getMessage());
        // The custom format registered from test sources is listed alongside the built-ins.
        assertTrue(error.getMessage().contains("dotenv"), error.getMessage());
    }

    @Test void formatPropertyPinsTheParserRegardlessOfFileName() throws Exception {
        Path file = directory.resolve("application.conf");
        Files.writeString(file, "{\"game\":{\"server\":{\"port\":9100}}}");

        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(file.toString()).property("format", "json").build())) {
            assertEquals(9100, center.snapshot().getInt("game.server.port", 0));
        }
    }

    @Test void pingFailsWhenARequiredFileDisappears() throws Exception {
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "name=mana\n");
        var source = new LocalConfigProvider.LocalSource(ConfigOptions.builder("local")
                .resource(file.toString()).build());

        assertDoesNotThrow(source::ping);
        Files.delete(file);
        assertThrows(ConfigException.class, source::ping);
    }

    @Test void collapsesABurstOfWritesIntoFewerReloads() throws Exception {
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "value=0\n");
        var source = new LocalConfigProvider.LocalSource(ConfigOptions.builder("local")
                .resource(file.toString()).property("debounceMillis", "300").build());
        AtomicInteger publishes = new AtomicInteger();
        AtomicReference<String> latest = new AtomicReference<>();
        AutoCloseable watch = source.watch(values -> {
            publishes.incrementAndGet();
            latest.set(values.get("value"));
        }, error -> { });
        try {
            for (int value = 1; value <= 5; value++) Files.writeString(file, "value=" + value + "\n");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"5".equals(latest.get()) && System.nanoTime() < deadline) Thread.onSpinWait();

            assertEquals("5", latest.get());
            // A burst of writes settles into a reload of the final state rather than one per event.
            assertTrue(publishes.get() < 5, "expected coalescing, got " + publishes.get() + " publishes");
        } finally {
            watch.close();
            source.close();
        }
    }
}
