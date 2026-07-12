package cn.managame.config.local;

import cn.managame.config.ConfigCenter;
import cn.managame.config.ConfigException;
import cn.managame.config.ConfigFactory;
import cn.managame.config.ConfigOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test void canIgnoreMissingOptionalFile() {
        try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(directory.resolve("missing.properties").toString()).property("required", "false").build())) {
            assertTrue(center.snapshot().values().isEmpty());
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
        assertThrows(ConfigException.class, () -> JsonDocument.parse("[1, 2, 3]"));
    }
}
