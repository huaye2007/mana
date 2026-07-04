package cn.managame.config.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadUtf8EncodedValues() throws IOException {
        Path file = tempDir.resolve("config.properties");
        Files.writeString(file, "server.name=游戏服\nfeature=测试\n", StandardCharsets.UTF_8);

        PropertiesLocalConfigLoader loader = new PropertiesLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("游戏服", result.get("server.name"));
        assertEquals("测试", result.get("feature"));
    }

    @Test
    void shouldStillSupportUnicodeEscapes() throws IOException {
        Path file = tempDir.resolve("config.properties");
        Files.writeString(file, "key=\\u4e2d\\u6587\n", StandardCharsets.UTF_8);

        PropertiesLocalConfigLoader loader = new PropertiesLocalConfigLoader(file.toString());
        Map<String, String> result = loader.load();

        assertEquals("中文", result.get("key"));
    }

    @Test
    void shouldReturnEmptyForNonexistentFile() {
        PropertiesLocalConfigLoader loader = new PropertiesLocalConfigLoader(
                tempDir.resolve("missing.properties").toString());

        assertTrue(loader.load().isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankPath() {
        assertTrue(new PropertiesLocalConfigLoader("").load().isEmpty());
        assertTrue(new PropertiesLocalConfigLoader(null).load().isEmpty());
    }
}
