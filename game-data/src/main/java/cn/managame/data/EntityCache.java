package cn.managame.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Repository cache that never evicts an entry with unsaved changes. Storage is therefore read only
 * for keys that have nothing pending, and a reload can never return data older than memory.
 * Mutations run inside {@link #update}, atomically with eviction of the same key; eviction runs only on
 * the persistence thread between flushes, when every change is either saved or pending again.
 */
final class EntityCache<K, V> {
    private static final class Entry<V> {
        final V value;
        volatile long lastAccess;
        Entry(V value) { this.value = value; lastAccess = System.nanoTime(); }
    }
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long idleNanos;
    // Access time is refreshed at most once per touchNanos, so threads reading the same hot entity
    // do not keep writing (and invalidating) its cache line; eviction waits touchNanos longer to compensate.
    private final long touchNanos;

    EntityCache(long idleNanos) {
        this.idleNanos = idleNanos;
        touchNanos = Math.min(idleNanos / 16, TimeUnit.SECONDS.toNanos(1));
    }
    private void touch(Entry<V> entry) {
        long now = System.nanoTime();
        if (now - entry.lastAccess > touchNanos) entry.lastAccess = now;
    }

    /** Loads a missing key once; concurrent callers of the same key wait for that load. */
    V get(K key, Function<K, V> loader) {
        Entry<V> entry = entries.get(key);
        if (entry == null) entry = entries.computeIfAbsent(key, k -> new Entry<>(loader.apply(k)));
        else touch(entry);
        return entry.value;
    }
    /**
     * Applies a change atomically with eviction of this key. {@code change} receives the current value
     * (null when absent) and returns the value to keep; a present value may be returned unchanged.
     */
    void update(K key, UnaryOperator<V> change) {
        entries.compute(key, (k, current) -> {
            V previous = current == null ? null : current.value;
            V next = change.apply(previous);
            if (current != null && next == previous) { touch(current); return current; }
            return new Entry<>(next);
        });
    }
    /**
     * Evicts entries idle for the configured time unless they still have unsaved changes. The clock is read
     * before {@code dirtyKeys} builds its view, so a change that view misses was recorded later and has
     * already refreshed its entry's access time, which keeps the entry.
     */
    void sweep(Supplier<Predicate<K>> dirtyKeys) {
        long now = System.nanoTime(), limit = idleNanos + touchNanos;
        Predicate<K> dirty = dirtyKeys.get();
        for (var e : entries.entrySet()) {
            if (now - e.getValue().lastAccess < limit) continue;
            entries.computeIfPresent(e.getKey(), (key, current) ->
                    now - current.lastAccess >= limit && !dirty.test(key) ? null : current);
        }
    }
    long size() { return entries.size(); }
}
