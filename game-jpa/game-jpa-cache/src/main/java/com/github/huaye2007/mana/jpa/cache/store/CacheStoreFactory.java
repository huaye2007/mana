package com.github.huaye2007.mana.jpa.cache.store;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;

public interface CacheStoreFactory {

    <K, V> CacheStore<K, V> create(CacheConfig config);
}
