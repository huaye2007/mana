package cn.managame.config;

import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * Merges several sources into one, later sources overriding earlier ones.
 *
 * <p>Each source keeps its own cached values, so an update from one layer re-merges from cache
 * instead of re-reading the others. That keeps a push from a remote backend free of extra requests,
 * and keeps a file change from touching the network at all.</p>
 *
 * <p>The merged view is unversioned: revisions from different backends are not comparable, so the
 * center falls back to content comparison. A single-layer stack is never wrapped in a composite, so
 * a lone Etcd layer keeps its native revision ordering.</p>
 */
final class CompositeConfigSource implements ConfigSource {
    private final List<ConfigSource> sources;
    private final List<String> names;
    private final AtomicReferenceArray<Map<String, String>> layerValues;
    private final ExecutorService loadExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("config-layer-load-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    CompositeConfigSource(List<ConfigSource> sources, List<String> names) {
        if (sources.size() < 2) throw new IllegalArgumentException("composite needs at least two sources");
        if (sources.size() != names.size()) throw new IllegalArgumentException("one name per source is required");
        this.sources = List.copyOf(sources);
        this.names = List.copyOf(names);
        layerValues = new AtomicReferenceArray<>(this.sources.size());
        for (int index = 0; index < this.sources.size(); index++) layerValues.set(index, Map.of());
    }

    @Override public Map<String, String> load() throws Exception {
        // Layers are independent, so the startup cost is one round trip, not one per backend.
        List<CompletableFuture<Map<String, String>>> pending = new ArrayList<>(sources.size());
        for (ConfigSource source : sources) {
            pending.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return source.loadData().values();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, loadExecutor));
        }
        List<Map<String, String>> loaded = join(pending, "cannot load config layer");
        for (int index = 0; index < loaded.size(); index++) layerValues.set(index, loaded.get(index));
        return merged();
    }

    @Override public ConfigData loadData() throws Exception { return ConfigData.unversioned(load()); }

    @Override public void ping() throws Exception {
        List<CompletableFuture<Void>> pending = new ArrayList<>(sources.size());
        for (ConfigSource source : sources) {
            pending.add(CompletableFuture.runAsync(() -> {
                try {
                    source.ping();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, loadExecutor));
        }
        join(pending, "config layer is unreachable");
    }

    @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
        List<AutoCloseable> handles = new ArrayList<>(sources.size());
        AtomicBoolean failed = new AtomicBoolean();
        Consumer<Throwable> reportOnce = error -> { if (failed.compareAndSet(false, true)) onError.accept(error); };
        try {
            for (int index = 0; index < sources.size(); index++) {
                int layer = index;
                handles.add(sources.get(index).watch(
                        values -> publish(layer, values, onUpdate), reportOnce));
            }
        } catch (Exception | Error error) {
            closeAll(handles, error);
            throw error instanceof RuntimeException runtime ? runtime : new ConfigException("cannot watch config layers", error);
        }
        return () -> closeAll(handles, null);
    }

    /** Replaces one layer's values and republishes the merge. Ordering across layers is kept by the lock. */
    private synchronized void publish(int layer, Map<String, String> values, Consumer<Map<String, String>> onUpdate) {
        layerValues.set(layer, Map.copyOf(values));
        onUpdate.accept(merged());
    }

    private Map<String, String> merged() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (int index = 0; index < layerValues.length(); index++) merged.putAll(layerValues.get(index));
        return Map.copyOf(merged);
    }

    /** Walks the layers in the same order as the merge, so the last writer of a key is the winner. */
    @Override public Map<String, String> origins() {
        Map<String, String> origins = new LinkedHashMap<>();
        for (int index = 0; index < layerValues.length(); index++) {
            String layer = names.get(index);
            layerValues.get(index).keySet().forEach(key -> origins.put(key, layer));
        }
        return Map.copyOf(origins);
    }

    @Override public List<ConfigOrigin> explain(String key) {
        List<ConfigOrigin> contributions = new ArrayList<>();
        for (int index = 0; index < layerValues.length(); index++) {
            String value = layerValues.get(index).get(key);
            if (value != null) contributions.add(new ConfigOrigin(names.get(index), value));
        }
        return List.copyOf(contributions);
    }

    private static <T> List<T> join(List<CompletableFuture<T>> pending, String message) throws Exception {
        List<T> results = new ArrayList<>(pending.size());
        Exception failure = null;
        for (CompletableFuture<T> future : pending) {
            try {
                results.add(future.join());
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                Exception wrapped = cause instanceof Exception exception ? exception : new ExecutionException(cause);
                if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
                results.add(null);
            }
        }
        if (failure != null) throw new ConfigException(message, failure);
        return results;
    }

    private static void closeAll(List<AutoCloseable> handles, Throwable collectInto) {
        for (AutoCloseable handle : handles) {
            try {
                handle.close();
            } catch (Exception error) {
                if (collectInto != null) collectInto.addSuppressed(error);
            }
        }
    }

    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Exception failure = null;
        for (ConfigSource source : sources) {
            try {
                source.close();
            } catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        loadExecutor.shutdownNow();
        if (failure != null) throw failure;
    }

    @Override public String toString() { return "CompositeConfigSource" + names; }
}
