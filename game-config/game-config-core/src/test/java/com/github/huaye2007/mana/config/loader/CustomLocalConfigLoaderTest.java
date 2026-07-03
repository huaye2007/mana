package com.github.huaye2007.mana.config.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseKeyValuePairs() throws IOException {
        Path file = tempDir.resolve("config.conf");
        Files.writeString(file, "host=localhost\nport=8080");

        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("localhost", result.get("host"));
        assertEquals("8080", result.get("port"));
    }

    @Test
    void shouldSupportColonSeparator() throws IOException {
        Path file = tempDir.resolve("config.conf");
        Files.writeString(file, "host: localhost\nport: 8080");

        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("localhost", result.get("host"));
        assertEquals("8080", result.get("port"));
    }

    @Test
    void shouldHandleSections() throws IOException {
        Path file = tempDir.resolve("config.conf");
        Files.writeString(file, "[server]\nhost=0.0.0.0\nport=8080\n\n[database]\nurl=jdbc:mysql://localhost/db");

        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("0.0.0.0", result.get("server.host"));
        assertEquals("8080", result.get("server.port"));
        assertEquals("jdbc:mysql://localhost/db", result.get("database.url"));
    }

    @Test
    void shouldSkipCommentsAndBlanks() throws IOException {
        Path file = tempDir.resolve("config.conf");
        Files.writeString(file, "# hash comment\nkey=value\n\n// double slash comment\nfoo=bar\n; semicolon line");

        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("value", result.get("key"));
        assertEquals("bar", result.get("foo"));
        assertEquals(2, result.size());
    }

    @Test
    void shouldSupportConfCfgAndIniExtensions() {
        CustomLocalConfigLoader loader = new CustomLocalConfigLoader();
        assertTrue(loader.supports("conf"));
        assertTrue(loader.supports("cfg"));
        assertTrue(loader.supports("ini"));
        assertFalse(loader.supports("json"));
        assertFalse(loader.supports("properties"));
    }

    @Test
    void shouldReturnEmptyForNonexistentFile() {
        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(tempDir.resolve("nonexistent.conf").toString());
        Map<String, String> result = loader.load();
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldTrimKeysAndValues() throws IOException {
        Path file = tempDir.resolve("config.conf");
        Files.writeString(file, "  key  =  value  ");

        CustomLocalConfigLoader loader = new CustomLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("value", result.get("key"));
    }
}
