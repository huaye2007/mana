package cn.managame.config.source;

import cn.managame.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDirectoryConfigSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldLoadFilesInStablePathOrder() throws Exception {
        Files.writeString(tempDir.resolve("10-base.properties"), "shared=base\nbase.only=true\n");
        Files.writeString(tempDir.resolve("20-override.json"), "{\"shared\":\"override\",\"json.only\":true}");

        LocalDirectoryConfigSource source = new LocalDirectoryConfigSource(tempDir.toString());
        Map<String, String> loaded = source.load();

        assertEquals("override", loaded.get("shared"));
        assertEquals("true", loaded.get("base.only"));
        assertEquals("true", loaded.get("json.only"));
        assertThrows(UnsupportedOperationException.class, () -> loaded.put("new", "value"));
    }

    @Test
    void shouldIgnoreNestedFilesWhenRecursiveIsDisabled() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(nested.resolve("nested.properties"), "nested=true\n");

        LocalDirectoryConfigSource source = new LocalDirectoryConfigSource(tempDir.toString());

        assertTrue(source.load().isEmpty());
    }

    @Test
    void shouldLoadNestedFilesWhenRecursiveIsEnabled() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(nested.resolve("nested.properties"), "nested=true\n");

        LocalDirectoryConfigSource source = new LocalDirectoryConfigSource(tempDir.toString(), true);

        assertEquals("true", source.load().get("nested"));
    }

    @Test
    void shouldFilterFilesByAllowedExtensionsWhenConfigured() throws Exception {
        Files.writeString(tempDir.resolve("application.properties"), "mode=properties\n");
        Files.writeString(tempDir.resolve("override.json"), "{\"mode\":\"json\"}");
        Files.writeString(tempDir.resolve("notes.txt"), "mode=text\n");

        LocalDirectoryConfigSource source = new LocalDirectoryConfigSource(
                tempDir.toString(),
                false,
                Set.of(".properties", "json"));

        assertEquals("json", source.load().get("mode"));
    }

    @Test
    void shouldReturnEmptyForMissingOrBlankDirectory() {
        assertTrue(new LocalDirectoryConfigSource(null).load().isEmpty());
        assertTrue(new LocalDirectoryConfigSource(" ").load().isEmpty());
        assertTrue(new LocalDirectoryConfigSource(tempDir.resolve("missing").toString()).load().isEmpty());
    }

    @Test
    void shouldRejectPathThatIsNotDirectory() throws Exception {
        Path file = tempDir.resolve("config.properties");
        Files.writeString(file, "enabled=true\n");

        assertThrows(ConfigOperationException.class, () -> new LocalDirectoryConfigSource(file.toString()).load());
    }
}
