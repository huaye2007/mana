package cn.managame.config.local;

import cn.managame.config.ConfigCenter;
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
}
