package com.github.huaye2007.mana.config.loader;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseSimpleObject() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"host\": \"localhost\", \"port\": 8080}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("localhost", result.get("host"));
        assertEquals("8080", result.get("port"));
    }

    @Test
    void shouldFlattenNestedObjects() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"server\": {\"host\": \"0.0.0.0\", \"port\": 8080}}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("0.0.0.0", result.get("server.host"));
        assertEquals("8080", result.get("server.port"));
    }

    @Test
    void shouldHandleBooleanAndNull() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"debug\": true, \"cache\": null, \"empty\": false}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("true", result.get("debug"));
        assertEquals("null", result.get("cache"));
        assertEquals("false", result.get("empty"));
    }

    @Test
    void shouldHandleArrays() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"servers\": [\"s1\", \"s2\", \"s3\"]}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("s1", result.get("servers.0"));
        assertEquals("s2", result.get("servers.1"));
        assertEquals("s3", result.get("servers.2"));
    }

    @Test
    void shouldHandleEmptyObject() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForNonexistentFile() {
        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(tempDir.resolve("nonexistent.json").toString());
        Map<String, String> result = loader.load();
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRejectMalformedJson() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{bad json}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        assertThrows(ConfigOperationException.class, loader::load);
    }

    @Test
    void shouldRejectInvalidLiteralTokens() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"enabled\": tru}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        assertThrows(ConfigOperationException.class, loader::load);
    }

    @Test
    void shouldRejectInvalidNumbers() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"port\": 01}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        assertThrows(ConfigOperationException.class, loader::load);
    }

    @Test
    void shouldRejectNonObjectRoot() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "[\"server\"]");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(file.toString());
        assertThrows(ConfigOperationException.class, loader::load);
    }

    @Test
    void shouldSupportJsonExtension() {
        JsonLocalConfigLoader loader = new JsonLocalConfigLoader();
        assertTrue(loader.supports("json"));
        assertFalse(loader.supports("yml"));
        assertFalse(loader.supports("properties"));
    }
}
