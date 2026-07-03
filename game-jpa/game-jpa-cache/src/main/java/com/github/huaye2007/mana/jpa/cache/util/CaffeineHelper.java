package com.github.huaye2007.mana.jpa.cache.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.huaye2007.mana.jpa.cache.CacheConfig;

/**
 * Caffeine 缓存构建工具。
 * 提取各模块重复的 buildCache 逻辑，统一缓存构建方式。
 */
public final class CaffeineHelper {

    private CaffeineHelper() {}

    /**
     * 根据 CacheConfig 构建 Caffeine Cache。
     *
     * @param config 缓存配置
     * @param <K>    缓存键类型
     * @param <V>    缓存值类型
     * @return 构建好的 Cache 实例
     */
    public static <K, V> Cache<K, V> buildCache(CacheConfig config) {
        var builder = Caffeine.newBuilder().maximumSize(config.maximumSize());
        if (config.expireAfterWrite() != null) {
            builder.expireAfterWrite(config.expireAfterWrite());
        }
        if (config.expireAfterAccess() != null) {
            builder.expireAfterAccess(config.expireAfterAccess());
        }
        return builder.build();
    }
}
