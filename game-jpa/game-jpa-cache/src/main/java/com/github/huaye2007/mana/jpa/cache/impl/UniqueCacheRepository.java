package com.github.huaye2007.mana.jpa.cache.impl;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.IUniqueCacheRepository;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.cache.store.CacheStore;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;

import java.util.List;
import java.util.function.Function;

/**
 * 唯一键缓存实现。
 * 以实体主键作为缓存 key，每个 key 对应一条记录。
 * 内部使用 Caffeine 作为本地缓存，支持过期策略和容量限制。
 * 变更通过 {@link WriteTaskSubmitter} SPI 提交，由外部刷盘调度器统一写入。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public class UniqueCacheRepository<T, ID> implements IUniqueCacheRepository<T, ID> {

    private static final Object DELETED_MARKER = new Object();

    private final CacheStore<ID, T> cache;
    private final WriteTaskSubmitter writeSubmitter;
    private final String entityName;
    private final Function<ID, T> dbLoader;
    private final Function<T, ID> idExtractor;
    private final NewRolePolicy newRolePolicy;

    /** 全量加载器（用于 warmUpAll） */
    private final java.util.function.Supplier<List<T>> allLoader;

    /**
     * @param writeSubmitter 写入提交器（通过 SPI 解耦具体实现）
     * @param entityName     内部写实体名（用于写通道查找与提交期路由）
     * @param dbLoader       数据库加载函数（缓存未命中时穿透）
     * @param idExtractor    从实体提取主键
     * @param allLoader      全量加载函数（用于 warmUpAll，可为 null）
     * @param config         缓存配置
     */
    public UniqueCacheRepository(WriteTaskSubmitter writeSubmitter,
                                 String entityName,
                                 Function<ID, T> dbLoader,
                                 Function<T, ID> idExtractor,
                                 java.util.function.Supplier<List<T>> allLoader,
                                 CacheConfig config) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, allLoader, config, NewRolePolicy.disabled());
    }

    public UniqueCacheRepository(WriteTaskSubmitter writeSubmitter,
                                 String entityName,
                                 Function<ID, T> dbLoader,
                                 Function<T, ID> idExtractor,
                                 java.util.function.Supplier<List<T>> allLoader,
                                 CacheConfig config,
                                 NewRolePolicy newRolePolicy) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, allLoader, config,
                config.cacheStoreFactory().create(config), newRolePolicy);
    }

    public UniqueCacheRepository(WriteTaskSubmitter writeSubmitter,
                                 String entityName,
                                 Function<ID, T> dbLoader,
                                 Function<T, ID> idExtractor,
                                 java.util.function.Supplier<List<T>> allLoader,
                                 CacheConfig config,
                                 CacheStore<ID, T> cache) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, allLoader, config, cache,
                NewRolePolicy.disabled());
    }

    public UniqueCacheRepository(WriteTaskSubmitter writeSubmitter,
                                 String entityName,
                                 Function<ID, T> dbLoader,
                                 Function<T, ID> idExtractor,
                                 java.util.function.Supplier<List<T>> allLoader,
                                 CacheConfig config,
                                 CacheStore<ID, T> cache,
                                 NewRolePolicy newRolePolicy) {
        this.writeSubmitter = writeSubmitter;
        this.entityName = entityName;
        this.dbLoader = dbLoader;
        this.idExtractor = idExtractor;
        this.allLoader = allLoader;
        this.newRolePolicy = newRolePolicy != null ? newRolePolicy : NewRolePolicy.disabled();
        this.cache = cache;
    }

    @Override
    public T cacheLoad(ID id) {
        return cacheLoad(id, dbLoader, id);
    }

    public T cacheLoad(ID id, Function<ID, T> routedLoader) {
        return cacheLoad(id, routedLoader, id);
    }

    public T cacheLoad(ID id, Function<ID, T> routedLoader, Object roleLookupKey) {
        T cached = cache.getIfPresent(id);
        if (cached != null) {
            return isDeletedMarker(cached) ? null : cached;
        }
        if (newRolePolicy.skipLoad(roleLookupKey)) {
            return null;
        }
        T value = cache.get(id, routedLoader::apply);
        return isDeletedMarker(value) ? null : value;
    }

    @Override
    public void cacheInsert(T entity) {
        ID id = idExtractor.apply(entity);
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.INSERT, entity, id);
        cache.put(id, entity);
    }

    @Override
    public void cacheUpdate(T entity) {
        ID id = idExtractor.apply(entity);
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.UPDATE, entity, id);
        cache.put(id, entity);
    }

    @Override
    public void cacheDelete(ID id) {
        T cached = cache.getIfPresent(id);
        T deletedEntity = isDeletedMarker(cached) ? null : cached;
        cacheDeleteWithEntity(id, deletedEntity);
    }

    public void cacheDeleteWithEntity(ID id, T deletedEntity) {
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.DELETE, deletedEntity, id);
        cache.put(id, deletedMarker());
    }

    public T cachePeek(ID id) {
        T cached = cache.getIfPresent(id);
        return isDeletedMarker(cached) ? null : cached;
    }

    @Override
    public void evict(ID id) {
        cache.invalidate(id);
    }

    @Override
    public void evictAll() {
        cache.invalidateAll();
    }

    @Override
    public void warmUp(List<ID> ids) {
        for (ID id : ids) {
            T entity = dbLoader.apply(id);
            if (entity != null) {
                cache.put(id, entity);
            }
        }
    }

    @Override
    public boolean supportsWarmUpAll() {
        return allLoader != null;
    }

    @Override
    public void warmUpAll() {
        if (allLoader == null) {
            throw new IllegalStateException("warmUpAll requires an allLoader for entity: " + entityName);
        }
        List<T> all = allLoader.get();
        for (T entity : all) {
            ID id = idExtractor.apply(entity);
            cache.put(id, entity);
        }
    }

    /** 获取当前缓存大小（用于监控） */
    public int size() {
        return (int) cache.estimatedSize();
    }

    @SuppressWarnings("unchecked")
    private T deletedMarker() {
        return (T) DELETED_MARKER;
    }

    private boolean isDeletedMarker(Object value) {
        return value == DELETED_MARKER;
    }
}
