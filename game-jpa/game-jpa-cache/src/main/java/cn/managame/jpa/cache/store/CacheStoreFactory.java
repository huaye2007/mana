package cn.managame.jpa.cache.store;

import cn.managame.jpa.cache.CacheConfig;

public interface CacheStoreFactory {

    <K, V> CacheStore<K, V> create(CacheConfig config);
}
