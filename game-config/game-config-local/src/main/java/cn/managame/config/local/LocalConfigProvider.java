package cn.managame.config.local;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.PropertiesDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LocalConfigProvider implements ConfigProvider {
    @Override public String type() { return "local"; }
    @Override public ConfigSource create(ConfigOptions options) { return new LocalSource(options); }

    static final class LocalSource implements ConfigSource {
        private final List<Path> files;
        private final boolean required;
        private final AtomicBoolean closed = new AtomicBoolean();
        private WatchService watchService;
        private Thread watchThread;

        LocalSource(ConfigOptions options) {
            files = options.resources().stream().map(Path::of).map(Path::toAbsolutePath).map(Path::normalize).toList();
            required = Boolean.parseBoolean(options.property("required", "true"));
        }

        @Override public Map<String, String> load() {
            Map<String, String> merged = new LinkedHashMap<>();
            for (Path file : files) {
                if (!Files.exists(file)) {
                    if (required) throw new ConfigException("local config file does not exist: " + file);
                    continue;
                }
                if (!Files.isRegularFile(file)) throw new ConfigException("local config resource is not a file: " + file);
                try {
                    merged.putAll(PropertiesDocument.parse(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new ConfigException("cannot read local config: " + file, e);
                }
            }
            return Map.copyOf(merged);
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                           Consumer<Throwable> onError) throws IOException {
            if (closed.get()) throw new IllegalStateException("local config source is closed");
            watchService = FileSystems.getDefault().newWatchService();
            Map<WatchKey, Path> directories = new HashMap<>();
            Set<Path> targets = Set.copyOf(files);
            for (Path directory : files.stream().map(Path::getParent).distinct().toList()) {
                if (directory != null && Files.isDirectory(directory)) {
                    WatchKey key = directory.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    directories.put(key, directory);
                }
            }
            watchThread = Thread.ofPlatform().daemon().name("game-config-local-watch").start(() -> {
                while (!closed.get()) {
                    try {
                        WatchKey key = watchService.take();
                        Path directory = directories.get(key);
                        boolean changed = key.pollEvents().stream()
                                .filter(event -> event.context() instanceof Path)
                                .map(event -> directory.resolve((Path) event.context()).toAbsolutePath().normalize())
                                .anyMatch(targets::contains);
                        if (!key.reset()) directories.remove(key);
                        if (changed) onUpdate.accept(load());
                    } catch (ClosedWatchServiceException e) {
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable e) {
                        onError.accept(e);
                    }
                }
            });
            return this::stopWatch;
        }

        private synchronized void stopWatch() throws IOException {
            WatchService service = watchService;
            watchService = null;
            if (service != null) service.close();
            Thread thread = watchThread;
            watchThread = null;
            if (thread != null) thread.interrupt();
        }

        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) stopWatch();
        }
    }
}
