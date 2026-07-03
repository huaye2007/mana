package com.github.huaye2007.mana.config.local;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import com.github.huaye2007.mana.config.source.LocalDirectoryConfigSource;
import com.github.huaye2007.mana.config.source.LocalFileConfigSource;
import com.github.huaye2007.mana.config.spi.RemoteConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.copy;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.firstNonBlank;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.parseNonNegativeLong;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.safe;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * RemoteConfigProvider adapter for local business config files.
 */
public class LocalRemoteConfigProvider implements RemoteConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalRemoteConfigProvider.class);

    private final Object watchLock = new Object();
    private volatile WatchService watchService;
    private volatile Thread watchThread;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public Iterable<String> aliases() {
        return List.of("file", "directory");
    }

    @Override
    public boolean supportsPush() {
        return true;
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) {
        Properties props = safe(remoteProperties);
        Map<String, String> merged = new HashMap<>();
        boolean loadedAnyLocator = false;
        boolean failIfMissing = parseFailIfMissing(props);

        String file = props.getProperty("file");
        if (file != null && !file.isBlank()) {
            merged.putAll(loadFile(file.trim(), failIfMissing));
            loadedAnyLocator = true;
        }

        String files = props.getProperty("files");
        if (files != null && !files.isBlank()) {
            for (String segment : files.split(",")) {
                String path = segment.trim();
                if (!path.isEmpty()) {
                    merged.putAll(loadFile(path, failIfMissing));
                    loadedAnyLocator = true;
                }
            }
        }

        String directory = props.getProperty("directory");
        if (directory != null && !directory.isBlank()) {
            merged.putAll(loadDirectory(directory.trim(), parseRecursive(props), parseExtensions(props), failIfMissing));
            loadedAnyLocator = true;
        }

        String path = props.getProperty("path");
        if (path != null && !path.isBlank()) {
            merged.putAll(loadPath(path.trim(), parseRecursive(props), parseExtensions(props), failIfMissing));
            loadedAnyLocator = true;
        }

        if (!loadedAnyLocator) {
            throw new ConfigOperationException("Local config requires file, files, directory, or path");
        }
        return Map.copyOf(merged);
    }

    @Override
    public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) throws Exception {
        subscribe(remoteProperties, callback, null);
    }

    @Override
    public void subscribe(Properties remoteProperties,
                          Consumer<Map<String, String>> callback,
                          Consumer<Exception> errorCallback) throws Exception {
        Properties props = copyProperties(safeProperties(remoteProperties));
        Map<String, String> initialSnapshot = load(props);
        WatchMode watchMode = parseWatchMode(props);
        if (watchMode == WatchMode.MANUAL) {
            synchronized (watchLock) {
                closeWatchResources();
            }
            callback.accept(initialSnapshot);
            return;
        }

        Set<Path> signalFiles = resolveSignalFiles(props);
        Set<Path> watchDirectories = resolveSignalWatchDirectories(signalFiles);
        long debounceMillis = parseWatchDebounceMillis(props);
        synchronized (watchLock) {
            closeWatchResources();
            if (watchDirectories.isEmpty()) {
                callback.accept(initialSnapshot);
                return;
            }

            WatchService service = FileSystems.getDefault().newWatchService();
            Map<WatchKey, Path> keys = new HashMap<>();
            for (Path directory : watchDirectories) {
                registerDirectory(directory, service, keys);
            }

            watchService = service;
            watchThread = new Thread(
                    () -> watchLoop(
                            props,
                            callback,
                            service,
                            keys,
                            signalFiles,
                            debounceMillis,
                            errorCallback),
                    "local-config-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        }
        callback.accept(initialSnapshot);
    }

    @Override
    public void close() {
        synchronized (watchLock) {
            closeWatchResources();
        }
    }

    private Map<String, String> loadFile(String path, boolean failIfMissing) {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            if (failIfMissing) {
                throw new ConfigOperationException("Local config file does not exist: " + path);
            }
            return Map.of();
        }
        if (Files.isDirectory(resolved)) {
            throw new ConfigOperationException("Local config file path is a directory: " + path);
        }
        return new LocalFileConfigSource(path).load();
    }

    private Map<String, String> loadDirectory(
            String path,
            boolean recursive,
            Set<String> extensions,
            boolean failIfMissing) {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            if (failIfMissing) {
                throw new ConfigOperationException("Local config directory does not exist: " + path);
            }
            return Map.of();
        }
        return new LocalDirectoryConfigSource(path, recursive, extensions).load();
    }

    private Map<String, String> loadPath(
            String path,
            boolean recursive,
            Set<String> extensions,
            boolean failIfMissing) {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            if (failIfMissing) {
                throw new ConfigOperationException("Local config path does not exist: " + path);
            }
            return Map.of();
        }
        if (Files.isDirectory(resolved)) {
            return new LocalDirectoryConfigSource(path, recursive, extensions).load();
        }
        return new LocalFileConfigSource(path).load();
    }

    private boolean parseRecursive(Properties props) {
        String value = props.getProperty("recursive", "false");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private boolean parseFailIfMissing(Properties props) {
        String value = props.getProperty("failIfMissing", "false");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private WatchMode parseWatchMode(Properties props) {
        String value = props.getProperty("watchMode", "manual");
        if (value == null || value.isBlank()) {
            return WatchMode.MANUAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "manual", "none", "disabled", "off", "false" -> WatchMode.MANUAL;
            case "signal", "marker", "trigger", "commit" -> WatchMode.SIGNAL;
            default -> throw new ConfigOperationException("watchMode must be manual or signal: " + value);
        };
    }

    private long parseWatchDebounceMillis(Properties props) {
        String value = props.getProperty("watchDebounceMillis", "50");
        try {
            long debounceMillis = Long.parseLong(value);
            if (debounceMillis < 0) {
                throw new ConfigOperationException("watchDebounceMillis must not be negative: " + value);
            }
            return debounceMillis;
        } catch (NumberFormatException e) {
            throw new ConfigOperationException("watchDebounceMillis must be a valid number: " + value, e);
        }
    }

    private Set<String> parseExtensions(Properties props) {
        String value = props.getProperty("extensions");
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> extensions = new HashSet<>();
        for (String segment : value.split(",")) {
            String extension = segment.trim();
            if (!extension.isEmpty()) {
                extensions.add(extension);
            }
        }
        return extensions;
    }

    private Set<Path> resolveSignalFiles(Properties props) {
        String value = firstNonBlank(
                props.getProperty("signalFile"),
                props.getProperty("reloadSignalFile"),
                props.getProperty("triggerFile"),
                props.getProperty("signalFiles"));
        if (value == null) {
            throw new ConfigOperationException("Local config signal watch requires signalFile");
        }

        Set<Path> signalFiles = new HashSet<>();
        for (String segment : value.split(",")) {
            String path = segment.trim();
            if (path.isEmpty()) {
                continue;
            }
            Path signalFile = Path.of(path).toAbsolutePath().normalize();
            Path parent = signalFile.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new ConfigOperationException("Local config signal file parent does not exist: " + path);
            }
            signalFiles.add(signalFile);
        }
        if (signalFiles.isEmpty()) {
            throw new ConfigOperationException("Local config signal watch requires signalFile");
        }
        return signalFiles;
    }

    private Set<Path> resolveSignalWatchDirectories(Set<Path> signalFiles) {
        Set<Path> directories = new HashSet<>();
        for (Path signalFile : signalFiles) {
            Path parent = signalFile.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                directories.add(parent.toAbsolutePath().normalize());
            }
        }
        return directories;
    }

    private void registerDirectory(
            Path directory,
            WatchService service,
            Map<WatchKey, Path> keys) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return;
        }
        WatchKey key = normalized.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, normalized);
    }

    private void watchLoop(
            Properties props,
            Consumer<Map<String, String>> callback,
            WatchService service,
            Map<WatchKey, Path> keys,
            Set<Path> signalFiles,
            long debounceMillis,
            Consumer<Exception> errorCallback) {
        while (true) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            boolean shouldReload = drainKey(key, keys, signalFiles);
            if (keys.isEmpty()) {
                if (shouldReload) {
                    fireReload(props, callback, errorCallback);
                }
                return;
            }

            while (shouldReload && debounceMillis > 0) {
                WatchKey next;
                try {
                    next = service.poll(debounceMillis, TimeUnit.MILLISECONDS);
                } catch (ClosedWatchServiceException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (next == null) {
                    break;
                }
                shouldReload = drainKey(next, keys, signalFiles) || shouldReload;
                if (keys.isEmpty()) {
                    fireReload(props, callback, errorCallback);
                    return;
                }
            }

            if (shouldReload) {
                fireReload(props, callback, errorCallback);
            }
        }
    }

    private boolean drainKey(
            WatchKey key,
            Map<WatchKey, Path> keys,
            Set<Path> signalFiles) {
        Path directory = keys.get(key);
        boolean shouldReload = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                shouldReload = true;
                continue;
            }
            shouldReload = shouldReload || isSignalEvent(directory, event, signalFiles);
        }
        if (!key.reset()) {
            keys.remove(key);
        }
        return shouldReload;
    }

    private boolean isSignalEvent(Path directory, WatchEvent<?> event, Set<Path> signalFiles) {
        if (directory == null || !(event.context() instanceof Path changedPath)) {
            return false;
        }
        Path changed = directory.resolve(changedPath).toAbsolutePath().normalize();
        return signalFiles.contains(changed);
    }

    private void fireReload(Properties props,
                            Consumer<Map<String, String>> callback,
                            Consumer<Exception> errorCallback) {
        try {
            callback.accept(load(props));
        } catch (RuntimeException e) {
            log.warn("Failed to reload local config after signal file change", e);
            reportPushError(errorCallback, e);
        }
    }

    private void reportPushError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            log.warn("Local config push error handler threw exception", handlerError);
        }
    }

    private void closeWatchResources() {
        WatchService service = watchService;
        watchService = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.warn("Failed to close local config watch service", e);
            }
        }

        Thread thread = watchThread;
        watchThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private Properties safeProperties(Properties remoteProperties) {
        return remoteProperties == null ? new Properties() : remoteProperties;
    }

    private Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private enum WatchMode {
        MANUAL,
        SIGNAL
    }
}
