package cn.managame.jpa.cache.store;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public interface CacheStore<K, V> {

    V get(K key, Function<? super K, ? extends V> loader);

    V getIfPresent(K key);

    void put(K key, V value);

    void invalidate(K key);

    void invalidateAll();

    ConcurrentMap<K, V> asMap();

    long estimatedSize();
}
