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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            String requiredValue = options.property("required", "true").trim();
            if (!requiredValue.equalsIgnoreCase("true") && !requiredValue.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("required must be true or false");
            }
            required = Boolean.parseBoolean(requiredValue);
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
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Map<String, String> values = parse(file, content);
                    for (String key : values.keySet()) {
                        if (merged.containsKey(key)) merged.keySet().removeIf(existing -> existing.startsWith(key + "["));
                    }
                    merged.putAll(values);
                } catch (IOException e) {
                    throw new ConfigException("cannot read local config: " + file, e);
                }
            }
            return Map.copyOf(merged);
        }

        private static Map<String, String> parse(Path file, String content) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) return JsonDocument.parse(content);
            return PropertiesDocument.parse(content);
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                           Consumer<Throwable> onError) throws IOException {
            if (closed.get()) throw new IllegalStateException("local config source is closed");
            if (watchService != null) throw new IllegalStateException("local config watch is already active");
            WatchService service = FileSystems.getDefault().newWatchService();
            Map<WatchKey, Path> directories = new HashMap<>();
            Set<Path> targets = Set.copyOf(files);
            try {
                Set<Path> watchedDirectories = new LinkedHashSet<>();
                for (Path file : files) {
                    Path directory = nearestExistingDirectory(file.getParent());
                    if (directory == null) throw new IOException("no existing directory can be watched");
                    watchedDirectories.add(directory);
                    if (directory.getParent() != null) watchedDirectories.add(directory.getParent());
                }
                for (Path directory : watchedDirectories) {
                    WatchKey key = directory.register(service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    directories.put(key, directory);
                }
            } catch (IOException | RuntimeException error) {
                service.close();
                throw error;
            }
            watchService = service;
            watchThread = Thread.ofPlatform().daemon().name("game-config-local-watch").start(() -> {
                while (!closed.get()) {
                    try {
                        WatchKey key = service.take();
                        Path directory = directories.get(key);
                        boolean changed = false;
                        boolean topologyChanged = directory == null;
                        for (var event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                topologyChanged = true;
                            } else if (directory != null && event.context() instanceof Path context) {
                                Path affected = directory.resolve(context).toAbsolutePath().normalize();
                                changed |= targets.contains(affected);
                                topologyChanged |= targets.stream()
                                        .anyMatch(target -> !target.equals(affected) && target.startsWith(affected));
                            }
                        }
                        if (!key.reset()) {
                            directories.remove(key);
                            topologyChanged = true;
                        }
                        if (changed) onUpdate.accept(load());
                        if (topologyChanged) {
                            onError.accept(new IOException("local config watch directory changed"));
                            return;
                        }
                    } catch (ClosedWatchServiceException e) {
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable e) {
                        onError.accept(e);
                        return;
                    }
                }
            });
            return this::stopWatch;
        }

        private static Path nearestExistingDirectory(Path directory) {
            for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
                if (Files.isDirectory(candidate)) return candidate;
            }
            return null;
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
