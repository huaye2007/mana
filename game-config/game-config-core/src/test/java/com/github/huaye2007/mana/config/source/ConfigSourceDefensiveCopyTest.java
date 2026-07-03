package com.github.huaye2007.mana.config.source;

import com.github.huaye2007.mana.config.loader.LocalConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSourceDefensiveCopyTest {
    @Test
    void defaultSourceShouldCopyInputMap() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("mode", "prod");

        DefaultConfigSource source = new DefaultConfigSource(defaults);
        defaults.put("mode", "test");

        Map<String, String> loaded = source.load();
        assertEquals("prod", loaded.get("mode"));
        assertThrows(UnsupportedOperationException.class, () -> loaded.put("new", "value"));
    }

    @Test
    void jvmSourceShouldCopyCustomProperties() {
        Map<String, String> custom = new HashMap<>();
        custom.put("mode", "prod");

        JvmConfigSource source = new JvmConfigSource(false, custom);
        custom.put("mode", "test");

        assertEquals("prod", source.load().get("mode"));
    }

    @Test
    void commandLineSourceShouldCopyRawArgsAndParsedArgs() {
        List<String> rawArgs = new ArrayList<>(List.of("--mode=prod"));
        Map<String, String> parsedArgs = new HashMap<>();
        parsedArgs.put("region", "cn");

        CommandLineConfigSource source = new CommandLineConfigSource(rawArgs, parsedArgs);
        rawArgs.set(0, "--mode=test");
        parsedArgs.put("region", "us");

        Map<String, String> loaded = source.load();
        assertEquals("prod", loaded.get("mode"));
        assertEquals("cn", loaded.get("region"));
    }

    @Test
    void localFileSourceShouldRejectNullLoader() {
        NullPointerException error = assertThrows(
                NullPointerException.class,
                () -> new LocalFileConfigSource((LocalConfigLoader) null));

        assertEquals("loader must not be null", error.getMessage());
    }

    @Test
    void localFileSourceShouldReturnImmutableCopyOfLoaderResult() {
        Map<String, String> loaderResult = new HashMap<>();
        loaderResult.put("mode", "prod");

        LocalFileConfigSource source = new LocalFileConfigSource(() -> loaderResult);
        Map<String, String> loaded = source.load();
        loaderResult.put("mode", "test");

        assertEquals("prod", loaded.get("mode"));
        assertThrows(UnsupportedOperationException.class, () -> loaded.put("new", "value"));
    }

    @Test
    void classpathSourceShouldFallbackWhenContextClassLoaderIsMissing() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);

            Map<String, String> loaded = new ClasspathConfigSource("classpath-config.properties").load();

            assertEquals("fromClasspath", loaded.get("classpath.key"));
            assertThrows(UnsupportedOperationException.class, () -> loaded.put("new", "value"));
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
