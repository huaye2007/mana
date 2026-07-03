package com.github.huaye2007.mana.config.starter;

import com.github.huaye2007.mana.config.manager.GameConfigManager;
import com.github.huaye2007.mana.config.factory.GameConfigOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameConfigStarterTest {
    @Test
    void shouldStartManagerWithCommonSourceOrder() throws Exception {
        Path localFile = Files.createTempFile("game-config-starter-", ".properties");
        Files.writeString(localFile, "server.port=9000\nfeature.source=local\n");

        GameConfigManager manager = GameConfigStarter.builder()
                .args(List.of("--server.port=9100"))
                .localFile(localFile.toString())
                .defaults(Map.of(
                        "server.port", "8080",
                        "feature.source", "default",
                        "fallback.enabled", "true"))
                .hotReload(false)
                .start();

        assertEquals(9100, manager.getInt("server.port", 0));
        assertEquals("local", manager.get("feature.source"));
        assertEquals(true, manager.getBoolean("fallback.enabled", false));
        manager.close();
    }

    @Test
    void shouldStartManagerWithLocalDirectorySource() throws Exception {
        Path localDirectory = Files.createTempDirectory("game-config-starter-dir-");
        Files.writeString(localDirectory.resolve("10-base.properties"), "server.port=9000\nfeature.source=directory\n");
        Files.writeString(localDirectory.resolve("20-override.properties"), "server.port=9200\n");

        GameConfigManager manager = GameConfigStarter.builder()
                .localDirectory(localDirectory.toString())
                .defaults(Map.of("server.port", "8080"))
                .hotReload(false)
                .start();

        assertEquals(9200, manager.getInt("server.port", 0));
        assertEquals("directory", manager.get("feature.source"));
        manager.close();
    }

    @Test
    void shouldPreferRemoteOverLocalSourcesByDefault() throws Exception {
        Path localFile = Files.createTempFile("game-config-starter-local-", ".properties");
        Path remoteFile = Files.createTempFile("game-config-starter-remote-", ".properties");
        Files.writeString(localFile, "shared=local\nlocal.only=true\n");
        Files.writeString(remoteFile, "shared=remote\nremote.only=true\n");

        GameConfigManager manager = GameConfigStarter.builder()
                .localFile(localFile.toString())
                .remote("local", Map.of("file", remoteFile.toString()))
                .hotReload(false)
                .start();

        assertEquals("remote", manager.get("shared"));
        assertEquals("true", manager.get("local.only"));
        assertEquals("true", manager.get("remote.only"));
        manager.close();
    }

    @Test
    void shouldNotExposeJvmOrEnvironmentByDefault() {
        String envKey = System.getenv().keySet().stream().findFirst().orElse(null);

        GameConfigManager manager = GameConfigStarter.builder()
                .hotReload(false)
                .start();

        assertNull(manager.get("java.version"));
        if (envKey != null) {
            assertNull(manager.get(envKey));
        }
        manager.close();
    }

    @Test
    void shouldExposeJvmAndEnvironmentOnlyWhenExplicitlyEnabled() {
        String propertyKey = "game.config.starter.test.property";
        String previousValue = System.getProperty(propertyKey);
        String envKey = System.getenv().keySet().stream().findFirst().orElse(null);
        System.setProperty(propertyKey, "fromJvm");
        try {
            GameConfigManager manager = GameConfigStarter.builder()
                    .systemProperties(true)
                    .environmentVariables(true)
                    .hotReload(false)
                    .start();

            assertEquals("fromJvm", manager.get(propertyKey));
            if (envKey != null) {
                assertEquals(System.getenv(envKey), manager.get(envKey));
            }
            manager.close();
        } finally {
            if (previousValue == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, previousValue);
            }
        }
    }

    @Test
    void shouldBuildOptionsWithFailFastAndHotReloadFlags() {
        GameConfigOptions options = GameConfigStarter.builder()
                .failFast(true)
                .hotReload(false)
                .buildOptions();

        assertEquals(true, options.isFailFast());
        assertEquals(false, options.isHotReloadEnabled());
    }
}
