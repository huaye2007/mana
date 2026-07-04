package cn.managame.jpa.cache.store;

import com.github.benmanes.caffeine.cache.Cache;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public class CaffeineCacheStore<K, V> implements CacheStore<K, V> {

    private final Cache<K, V> cache;

    public CaffeineCacheStore(Cache<K, V> cache) {
        this.cache = cache;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        return cache.get(key, loader);
    }

    @Override
    public V getIfPresent(K key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        cache.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
        return cache.asMap();
    }

    @Override
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
