package cn.managame.data;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Repository cache that never evicts an entry with unsaved changes. Storage is therefore read only
 * for keys that have nothing pending, and a reload can never return data older than memory.
 * Mutations run inside {@link #update}, atomically with eviction of the same key; eviction runs only on
 * the persistence thread between flushes, when every change is either saved or pending again.
 *
 * <p>Loads run outside the map's locks: one caller loads a missing key while concurrent callers of the same
 * key wait for that load, and callers of other keys are never blocked by it. A value installed by a
 * mutation during the load wins over the loaded one, because memory is newer than storage.
 */
final class EntityCache<K, V> {
    private static final class Entry<V> {
        final V value;
        volatile long lastAccess;
        Entry(V value) { this.value = value; lastAccess = System.nanoTime(); }
    }

    /** An in-flight load of one key; {@code owner} detects a loader that re-enters the same key. */
    private static final class Loading<V> extends CompletableFuture<V> {
        final Thread owner = Thread.currentThread();
        /** Set by a sweep that evicted this key while the load ran: the loaded value may be stale. */
        volatile boolean evicted;
    }

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, Loading<V>> loading = new ConcurrentHashMap<>();
    private final long idleNanos;
    // Access time is refreshed at most once per touchNanos, so threads reading the same hot entity
    // do not keep writing (and invalidating) its cache line; eviction waits touchNanos longer to compensate.
    private final long touchNanos;
    private final long maxEntries;

    EntityCache(long idleNanos, long maxEntries) {
        this.idleNanos = idleNanos;
        this.maxEntries = maxEntries;
        touchNanos = Math.min(idleNanos / 16, TimeUnit.SECONDS.toNanos(1));
    }

    private void touch(Entry<V> entry) {
        long now = System.nanoTime();
        if (now - entry.lastAccess > touchNanos) entry.lastAccess = now;
    }

    /** The cached value, or {@code loader}'s result for a missing key; concurrent callers of one key share a load. */
    V get(K key, Function<K, V> loader) {
        for (;;) {
            Entry<V> entry = entries.get(key);
            if (entry != null) {
                touch(entry);
                return entry.value;
            }
            Loading<V> mine = new Loading<>(), other = loading.putIfAbsent(key, mine);
            if (other != null) {
                if (other.owner == Thread.currentThread())
                    throw new IllegalStateException("Recursive load of the same key: " + key);
                await(other);      // then read the installed entry (or retry after a failed load)
                continue;
            }
            try {
                V value = load(key, loader, mine);
                mine.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                loading.remove(key, mine);
            }
        }
    }

    /** Loads outside any map lock and installs the result unless a mutation got there first. */
    private V load(K key, Function<K, V> loader, Loading<V> mine) {
        for (;;) {
            mine.evicted = false;
            V loaded = loader.apply(key);
            Object[] result = {null};
            boolean[] stale = {false};
            entries.compute(key, (k, current) -> {
                if (current != null) { result[0] = current.value; return current; }
                // An eviction during the load may have dropped a newer value that was saved meanwhile.
                if (mine.evicted) { stale[0] = true; return null; }
                result[0] = loaded;
                return new Entry<>(loaded);
            });
            if (!stale[0]) {
                @SuppressWarnings("unchecked") V value = (V) result[0];
                return value;
            }
        }
    }

    private static void await(CompletableFuture<?> load) {
        try {
            load.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent load", interrupted);
        } catch (ExecutionException | CompletionException failed) {
            // The loading caller reports its own failure; this caller retries the load itself.
        }
    }

    /** A change applied by {@link #update}; {@code cached} tells a cached null ("absent in storage") from no entry. */
    @FunctionalInterface
    interface Change<V> {
        V apply(boolean cached, V current);
    }

    /**
     * Applies a change atomically with eviction of this key. {@code change} receives whether the key is cached
     * and its value (null when absent), and returns the value to keep; a present value may be returned unchanged.
     * A change that throws leaves the entry as it was.
     */
    void update(K key, Change<V> change) {
        // A mutation never races a first load of its key: it waits for that load and then applies to its result.
        Loading<V> inFlight = loading.get(key);
        if (inFlight != null && inFlight.owner != Thread.currentThread()) await(inFlight);
        entries.compute(key, (k, current) -> {
            V previous = current == null ? null : current.value;
            V next = change.apply(current != null, previous);
            if (current != null && next == previous) { touch(current); return current; }
            return new Entry<>(next);
        });
    }

    /**
     * Evicts entries idle for the configured time unless they still have unsaved changes, and, when the cache
     * is over {@code maxEntries}, the least recently used clean entries on top of that. The clock is read
     * before {@code dirtyKeys} builds its view, so a change that view misses was recorded later and has
     * already refreshed its entry's access time, which keeps the entry.
     *
     * @param idleDue false on the ticks between idle sweeps: only the entry bound is enforced then
     */
    void sweep(Supplier<Predicate<K>> dirtyKeys, boolean idleDue) {
        if (!idleDue && entries.size() <= maxEntries) return;
        long now = System.nanoTime(), limit = idleNanos + touchNanos;
        Predicate<K> dirty = dirtyKeys.get();
        if (idleDue) for (var e : entries.entrySet()) {
            if (now - e.getValue().lastAccess < limit) continue;
            entries.computeIfPresent(e.getKey(), (key, current) ->
                    now - current.lastAccess < limit || dirty.test(key) ? current : evicted(key));
        }
        trim(dirty);
    }

    /**
     * Brings the cache back to {@code maxEntries} by dropping the clean entries that went longest unread.
     * Dirty entries are not evictable (D-CACHE-01), so a cache whose dirty entries alone exceed the bound
     * stays above it. One sweep never selects more than the bound itself: a large overshoot comes down over
     * the passes that follow rather than allocating a selection as large as the overshoot, and a cache that
     * fills faster than sweeps run settles somewhat above its bound instead of at it.
     */
    private void trim(Predicate<K> dirty) {
        long excess = entries.size() - maxEntries;
        if (excess <= 0) return;
        int budget = (int) Math.min(excess, maxEntries);
        var oldest = new PriorityQueue<Map.Entry<K, Entry<V>>>(budget,
                Comparator.comparingLong((Map.Entry<K, Entry<V>> e) -> e.getValue().lastAccess).reversed());
        for (var e : entries.entrySet()) {
            if (dirty.test(e.getKey())) continue;
            if (oldest.size() < budget) oldest.add(e);
            else if (e.getValue().lastAccess < oldest.peek().getValue().lastAccess) { oldest.poll(); oldest.add(e); }
        }
        for (var selected : oldest) {
            long seen = selected.getValue().lastAccess;
            // Read or changed since it was selected, or dirty after all: the entry stays and the next sweep decides.
            entries.computeIfPresent(selected.getKey(), (key, current) ->
                    current.lastAccess != seen || dirty.test(key) ? current : evicted(key));
        }
    }

    /** Tells an in-flight load that its value may be stale, then removes the entry by returning null. */
    private Entry<V> evicted(K key) {
        Loading<V> inFlight = loading.get(key);
        if (inFlight != null) inFlight.evicted = true;
        return null;
    }

    long size() { return entries.size(); }
}
