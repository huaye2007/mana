package com.github.huaye2007.mana.jpa.cache.store;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.huaye2007.mana.jpa.cache.CacheConfig;

public final class CaffeineCacheStoreFactory implements CacheStoreFactory {

    public static final CaffeineCacheStoreFactory INSTANCE = new CaffeineCacheStoreFactory();

    private CaffeineCacheStoreFactory() {
    }

    @Override
    public <K, V> CacheStore<K, V> create(CacheConfig config) {
        var builder = Caffeine.newBuilder();
        if (config.maximumSize() >= 0) {
            builder.maximumSize(config.maximumSize());
        }
        if (config.expireAfterWrite() != null) {
            builder.expireAfterWrite(config.expireAfterWrite());
        }
        if (config.expireAfterAccess() != null) {
            builder.expireAfterAccess(config.expireAfterAccess());
        }
        return new CaffeineCacheStore<>(builder.build());
    }
}
