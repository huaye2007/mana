package com.github.huaye2007.mana.config.local;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import com.github.huaye2007.mana.config.spi.RemoteConfigProvider;
import com.github.huaye2007.mana.config.spi.RemoteConfigProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRemoteConfigProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldLoadSingleFile() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "server.port=9000\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());

        Map<String, String> loaded = new LocalRemoteConfigProvider().load(props);

        assertEquals("9000", loaded.get("server.port"));
    }

    @Test
    void shouldLoadMultipleFilesInDeclaredOrder() throws Exception {
        Path base = tempDir.resolve("base.properties");
        Path override = tempDir.resolve("override.json");
        Files.writeString(base, "shared=base\nbase.only=true\n");
        Files.writeString(override, "{\"shared\":\"override\",\"json.only\":true}");
        Properties props = new Properties();
        props.setProperty("files", base + "," + override);

        Map<String, String> loaded = new LocalRemoteConfigProvider().load(props);

        assertEquals("override", loaded.get("shared"));
        assertEquals("true", loaded.get("base.only"));
        assertEquals("true", loaded.get("json.only"));
    }

    @Test
    void shouldLoadDirectoryRecursivelyWhenConfigured() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("conf.d").resolve("nested"));
        Files.writeString(nested.resolve("feature.properties"), "feature.enabled=true\n");
        Properties props = new Properties();
        props.setProperty("directory", tempDir.resolve("conf.d").toString());
        props.setProperty("recursive", "true");

        Map<String, String> loaded = new LocalRemoteConfigProvider().load(props);

        assertEquals("true", loaded.get("feature.enabled"));
    }

    @Test
    void shouldFilterDirectoryFilesByExtensions() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("conf.d"));
        Files.writeString(directory.resolve("10-base.properties"), "mode=properties\n");
        Files.writeString(directory.resolve("20-notes.txt"), "mode=text\n");
        Properties props = new Properties();
        props.setProperty("directory", directory.toString());
        props.setProperty("extensions", "properties,json");

        Map<String, String> loaded = new LocalRemoteConfigProvider().load(props);

        assertEquals("properties", loaded.get("mode"));
    }

    @Test
    void shouldAutoDetectPathKind() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "mode=file\n");
        Properties props = new Properties();
        props.setProperty("path", file.toString());

        assertEquals("file", new LocalRemoteConfigProvider().load(props).get("mode"));
    }

    @Test
    void shouldReturnEmptyWhenConfiguredFileIsMissingByDefault() {
        Properties props = new Properties();
        props.setProperty("file", tempDir.resolve("missing.properties").toString());

        assertTrue(new LocalRemoteConfigProvider().load(props).isEmpty());
    }

    @Test
    void shouldRejectMissingConfiguredFileWhenFailIfMissingEnabled() {
        Properties props = new Properties();
        props.setProperty("file", tempDir.resolve("missing.properties").toString());
        props.setProperty("failIfMissing", "true");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().load(props));

        assertTrue(error.getMessage().startsWith("Local config file does not exist:"));
    }

    @Test
    void shouldRejectMissingLocator() {
        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().load(null));

        assertEquals("Local config requires file, files, directory, or path", error.getMessage());
    }

    @Test
    void shouldBeDiscoverableByRemoteProviderFactory() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("local");

        assertTrue(provider instanceof LocalRemoteConfigProvider);
    }

    @Test
    void shouldNotPushOnFileChangeByDefault() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "mode=initial\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());
        LocalRemoteConfigProvider provider = new LocalRemoteConfigProvider();
        AtomicInteger callbacks = new AtomicInteger();

        provider.subscribe(props, ignored -> callbacks.incrementAndGet());
        assertEquals(1, callbacks.get());

        Files.writeString(file, "mode=updated\n");
        Thread.sleep(300L);
        assertEquals(1, callbacks.get());
        provider.close();
    }

    @Test
    void shouldReloadWholeDirectoryOnlyAfterSignalFileChanges() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("conf.d"));
        Path base = directory.resolve("10-base.properties");
        Path rules = directory.resolve("20-rules.properties");
        Path signal = tempDir.resolve("config.ready");
        Files.writeString(base, "version=1\nbase.only=true\n");
        Files.writeString(rules, "version=1\nrule.limit=10\n");
        Properties props = new Properties();
        props.setProperty("directory", directory.toString());
        props.setProperty("watchMode", "signal");
        props.setProperty("signalFile", signal.toString());
        props.setProperty("watchDebounceMillis", "0");
        LocalRemoteConfigProvider provider = new LocalRemoteConfigProvider();
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<Map<String, String>> pushed = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();

        provider.subscribe(props, snapshot -> {
            callbacks.incrementAndGet();
            if ("2".equals(snapshot.get("version")) && "20".equals(snapshot.get("rule.limit"))) {
                pushed.set(snapshot);
                changed.countDown();
            }
        });

        Files.writeString(base, "version=2\nbase.only=true\n");
        Files.writeString(rules, "version=2\nrule.limit=20\n");
        Thread.sleep(300L);
        assertEquals(1, callbacks.get());

        Files.writeString(signal, Long.toString(System.nanoTime()));

        assertTrue(changed.await(3, TimeUnit.SECONDS));
        assertEquals("2", pushed.get().get("version"));
        assertEquals("20", pushed.get().get("rule.limit"));
        provider.close();
    }

    @Test
    void shouldRejectInvalidWatchDebounceMillis() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Path signal = tempDir.resolve("config.ready");
        Files.writeString(file, "mode=initial\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());
        props.setProperty("watchMode", "signal");
        props.setProperty("signalFile", signal.toString());
        props.setProperty("watchDebounceMillis", "bad");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().subscribe(props, ignored -> { }));

        assertEquals("watchDebounceMillis must be a valid number: bad", error.getMessage());
    }

    @Test
    void shouldRejectNegativeWatchDebounceMillis() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Path signal = tempDir.resolve("config.ready");
        Files.writeString(file, "mode=initial\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());
        props.setProperty("watchMode", "signal");
        props.setProperty("signalFile", signal.toString());
        props.setProperty("watchDebounceMillis", "-1");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().subscribe(props, ignored -> { }));

        assertEquals("watchDebounceMillis must not be negative: -1", error.getMessage());
    }

    @Test
    void shouldRejectInvalidWatchMode() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "mode=initial\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());
        props.setProperty("watchMode", "files");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().subscribe(props, ignored -> { }));

        assertEquals("watchMode must be manual or signal: files", error.getMessage());
    }

    @Test
    void shouldRejectSignalModeWithoutSignalFile() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "mode=initial\n");
        Properties props = new Properties();
        props.setProperty("file", file.toString());
        props.setProperty("watchMode", "signal");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> new LocalRemoteConfigProvider().subscribe(props, ignored -> { }));

        assertEquals("Local config signal watch requires signalFile", error.getMessage());
    }
}
