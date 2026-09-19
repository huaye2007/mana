package cn.managame.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Thin Caffeine wrapper. Caffeine itself owns same-key load serialization. */
public final class EntityCache<K, V> {
    private final Cache<K, V> cache;

    public EntityCache(Duration expireAfterAccess) {
        this(expireAfterAccess, false);
    }

    public EntityCache(Duration expireAfterAccess, boolean resident) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (!resident && expireAfterAccess != null && !expireAfterAccess.isZero() && !expireAfterAccess.isNegative()) {
            builder.expireAfterAccess(expireAfterAccess);
        }
        @SuppressWarnings("unchecked")
        Cache<K, V> built = (Cache<K, V>) (Cache<?, ?>) builder.build();
        this.cache = built;
    }

    public V getIfPresent(K key) { return cache.getIfPresent(key); }

    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(loader, "loader");
        return cache.get(key, loader);
    }

    public void put(K key, V value) { cache.put(key, value); }
    public void remove(K key) { cache.invalidate(key); }
    public void clear() { cache.invalidateAll(); }
    public long estimatedSize() { return cache.estimatedSize(); }
    public Map<K, V> asMap() { return cache.asMap(); }

    public void computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        cache.asMap().computeIfPresent(key, remapping);
    }
}
