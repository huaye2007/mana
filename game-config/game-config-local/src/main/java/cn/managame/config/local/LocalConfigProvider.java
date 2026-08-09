package cn.managame.config.local;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigLayer;
import cn.managame.config.spi.ConfigFormat;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.ConfigFormats;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reads config documents from the local filesystem, hot-reloading on filesystem events.
 *
 * <p>Each resource is parsed by the {@link ConfigFormat} its name selects, so properties and JSON can
 * be mixed in one layer. Layer properties: {@code required} (default {@code true}) allows a file to
 * be absent, {@code debounceMillis} (default {@code 100}) sets the quiet period that collapses the
 * burst of events a single editor save produces, and {@code format} pins one format for all
 * resources.</p>
 */
public final class LocalConfigProvider implements ConfigProvider {
    @Override public String type() { return "local"; }
    @Override public ConfigSource create(ConfigLayer layer) { return new LocalSource(layer); }

    static final class LocalSource implements ConfigSource {
        private final List<Resource> resources;
        private final boolean required;
        private final long debounceMillis;
        private final AtomicBoolean closed = new AtomicBoolean();
        private WatchService watchService;
        private Thread watchThread;

        LocalSource(ConfigLayer layer) {
            resources = layer.requireResources().stream()
                    .map(resource -> new Resource(
                            Path.of(resource).toAbsolutePath().normalize(),
                            ConfigFormats.of(layer, resource)))
                    .toList();
            required = layer.booleanProperty("required", true);
            debounceMillis = layer.longProperty("debounceMillis", 100);
            if (debounceMillis < 0) throw new IllegalArgumentException("debounceMillis must not be negative");
        }

        private record Resource(Path file, ConfigFormat format) { }

        @Override public Map<String, String> load() {
            Map<String, String> merged = new LinkedHashMap<>();
            for (Resource resource : resources) {
                Path file = resource.file();
                if (!Files.exists(file)) {
                    if (required) throw new ConfigException("local config file does not exist: " + file);
                    continue;
                }
                if (!Files.isRegularFile(file)) throw new ConfigException("local config resource is not a file: " + file);
                Map<String, String> values;
                try {
                    values = resource.format().parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new ConfigException("cannot read local config: " + file, e);
                }
                dropStaleIndexedChildren(merged, values);
                merged.putAll(values);
            }
            return Map.copyOf(merged);
        }

        /**
         * Removes {@code key[i]} entries left by an earlier file whose {@code key} this file redefines,
         * so a shorter array does not keep the tail of a longer one.
         *
         * <p>One pass over each map rather than a scan of the merge per overriding key, which used to
         * make merging two large overlapping documents quadratic.</p>
         */
        private static void dropStaleIndexedChildren(Map<String, String> merged, Map<String, String> values) {
            if (merged.isEmpty()) return;
            Set<String> overridden = new HashSet<>();
            for (String key : values.keySet()) {
                if (merged.containsKey(key)) overridden.add(key);
            }
            if (overridden.isEmpty()) return;
            merged.keySet().removeIf(existing -> {
                int bracket = existing.indexOf('[');
                return bracket > 0 && overridden.contains(existing.substring(0, bracket));
            });
        }

        @Override public void ping() {
            for (Resource resource : resources) {
                Path file = resource.file();
                if (!Files.exists(file)) {
                    if (required) throw new ConfigException("local config file does not exist: " + file);
                    continue;
                }
                if (!Files.isReadable(file)) throw new ConfigException("local config file is not readable: " + file);
            }
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                          Consumer<Throwable> onError) throws IOException {
            if (closed.get()) throw new IllegalStateException("local config source is closed");
            if (watchService != null) throw new IllegalStateException("local config watch is already active");
            WatchService service = FileSystems.getDefault().newWatchService();
            Map<WatchKey, Path> directories = new HashMap<>();
            Set<Path> targets = new LinkedHashSet<>();
            resources.forEach(resource -> targets.add(resource.file()));
            try {
                Set<Path> watchedDirectories = new LinkedHashSet<>();
                for (Path file : targets) {
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
            watchThread = Thread.ofPlatform().daemon().name("game-config-local-watch")
                    .start(() -> watchLoop(service, directories, Set.copyOf(targets), onUpdate, onError));
            return this::stopWatch;
        }

        /**
         * Collapses filesystem events within a quiet period before reloading.
         *
         * <p>A single save typically produces several events, and a truncate-then-write save can be
         * observed mid-write. Waiting for the burst to settle turns that into one clean reload.</p>
         */
        private void watchLoop(WatchService service, Map<WatchKey, Path> directories, Set<Path> targets,
                               Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            boolean pending = false;
            long deadlineNanos = 0;
            while (!closed.get()) {
                try {
                    WatchKey key;
                    if (pending) {
                        long waitNanos = deadlineNanos - System.nanoTime();
                        key = waitNanos <= 0 ? null : service.poll(waitNanos, TimeUnit.NANOSECONDS);
                        if (key == null) {
                            pending = false;
                            if (!publish(onUpdate, onError)) return;
                            continue;
                        }
                    } else {
                        key = service.take();
                    }
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
                    if (changed) {
                        if (debounceMillis == 0) {
                            if (!publish(onUpdate, onError)) return;
                        } else {
                            pending = true;
                            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMillis);
                        }
                    }
                    if (topologyChanged) {
                        if (pending) publish(onUpdate, onError);
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
        }

        /**
         * Loads and publishes, retrying once before reporting a terminal failure.
         *
         * <p>A file caught mid-write parses as garbage; that is a transient condition, not a reason to
         * tear down the watch, so it gets one more chance after the writer has had a moment to finish.</p>
         */
        private boolean publish(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            Map<String, String> values;
            try {
                values = load();
            } catch (RuntimeException first) {
                try {
                    Thread.sleep(Math.max(20, debounceMillis));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                try {
                    values = load();
                } catch (RuntimeException second) {
                    if (second != first) second.addSuppressed(first);
                    onError.accept(second);
                    return false;
                }
            }
            onUpdate.accept(values);
            return true;
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

        @Override public String toString() {
            List<Path> files = new ArrayList<>(resources.size());
            resources.forEach(resource -> files.add(resource.file()));
            return "LocalSource" + files;
        }
    }
}
