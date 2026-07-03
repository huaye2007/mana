package com.github.huaye2007.mana.jpa.cache.impl;

import com.github.huaye2007.mana.jpa.cache.CacheCompositeKey;
import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.IMultiCacheRepository;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.cache.store.CacheStore;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 组合键缓存实现。
 * 通过 @CacheKey(order=N) 标记的字段值组成 CacheCompositeKey，
 * 每个组合键对应多条记录。
 * 变更通过 {@link WriteTaskSubmitter} SPI 提交，由外部刷盘调度器统一写入。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public class MultiCacheRepository<T, ID> implements IMultiCacheRepository<T, ID> {

    /** compositeKey → 该组合键下的所有实体（按主键索引） */
    private final CacheStore<CacheCompositeKey, Map<ID, T>> cache;
    private final WriteTaskSubmitter writeSubmitter;
    private final String entityName;
    private final Function<CacheCompositeKey, List<T>> dbLoader;
    private final Function<T, ID> idExtractor;
    private final Function<T, CacheCompositeKey> compositeKeyExtractor;
    private final Function<Object, Object> roleIdExtractor;
    private final NewRolePolicy newRolePolicy;

    /**
     * @param writeSubmitter        写入提交器（通过 SPI 解耦具体实现）
     * @param entityName            实体逻辑名
     * @param dbLoader              按组合键从数据库加载
     * @param idExtractor           从实体提取主键
     * @param compositeKeyExtractor 从实体提取组合缓存键
     * @param config                缓存配置
     */
    public MultiCacheRepository(WriteTaskSubmitter writeSubmitter,
            String entityName,
            Function<CacheCompositeKey, List<T>> dbLoader,
            Function<T, ID> idExtractor,
            Function<T, CacheCompositeKey> compositeKeyExtractor,
            CacheConfig config) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, compositeKeyExtractor, config,
                (Function<CacheCompositeKey, Object>) null, NewRolePolicy.disabled());
    }

    public MultiCacheRepository(WriteTaskSubmitter writeSubmitter,
            String entityName,
            Function<CacheCompositeKey, List<T>> dbLoader,
            Function<T, ID> idExtractor,
            Function<T, CacheCompositeKey> compositeKeyExtractor,
            CacheConfig config,
            Function<CacheCompositeKey, Object> roleIdExtractor) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, compositeKeyExtractor, config,
                roleIdExtractor, NewRolePolicy.disabled());
    }

    public MultiCacheRepository(WriteTaskSubmitter writeSubmitter,
            String entityName,
            Function<CacheCompositeKey, List<T>> dbLoader,
            Function<T, ID> idExtractor,
            Function<T, CacheCompositeKey> compositeKeyExtractor,
            CacheConfig config,
            Function<CacheCompositeKey, Object> roleIdExtractor,
            NewRolePolicy newRolePolicy) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, compositeKeyExtractor, config,
                config.cacheStoreFactory().create(config), roleIdExtractor, newRolePolicy);
    }

    public MultiCacheRepository(WriteTaskSubmitter writeSubmitter,
            String entityName,
            Function<CacheCompositeKey, List<T>> dbLoader,
            Function<T, ID> idExtractor,
            Function<T, CacheCompositeKey> compositeKeyExtractor,
            CacheConfig config,
            CacheStore<CacheCompositeKey, Map<ID, T>> cache) {
        this(writeSubmitter, entityName, dbLoader, idExtractor, compositeKeyExtractor, config, cache, null,
                NewRolePolicy.disabled());
    }

    public MultiCacheRepository(WriteTaskSubmitter writeSubmitter,
            String entityName,
            Function<CacheCompositeKey, List<T>> dbLoader,
            Function<T, ID> idExtractor,
            Function<T, CacheCompositeKey> compositeKeyExtractor,
            CacheConfig config,
            CacheStore<CacheCompositeKey, Map<ID, T>> cache,
            Function<CacheCompositeKey, Object> roleIdExtractor,
            NewRolePolicy newRolePolicy) {
        this.writeSubmitter = writeSubmitter;
        this.entityName = entityName;
        this.dbLoader = dbLoader;
        this.idExtractor = idExtractor;
        this.compositeKeyExtractor = compositeKeyExtractor;
        this.roleIdExtractor = roleIdExtractor != null ? key -> roleIdExtractor.apply((CacheCompositeKey) key) : null;
        this.newRolePolicy = newRolePolicy != null ? newRolePolicy : NewRolePolicy.disabled();
        this.cache = cache;
    }

    @Override
    public List<T> cacheLoad(CacheCompositeKey compositeKey) {
        Map<ID, T> cached = cache.getIfPresent(compositeKey);
        if (cached != null) {
            return toList(cached);
        }
        if (newRolePolicy.skipLoad(compositeKey, roleIdExtractor)) {
            return Collections.emptyList();
        }
        return toList(cache.get(compositeKey, this::loadBucketFromDb));
    }

    @Override
    public void cacheInsert(T entity) {
        CacheCompositeKey key = compositeKeyExtractor.apply(entity);
        ID id = idExtractor.apply(entity);
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.INSERT, entity, id);
        bucketForWrite(key).put(id, entity);
    }

    @Override
    public void cacheUpdate(T entity) {
        CacheCompositeKey key = compositeKeyExtractor.apply(entity);
        ID id = idExtractor.apply(entity);
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.UPDATE, entity, id);
        Map<CacheCompositeKey, Map<ID, T>> buckets = cache.asMap();
        Map<ID, T> bucket = buckets.get(key);
        if (bucket != null && bucket.containsKey(id)) {
            bucket.put(id, entity);
        }
    }

    @Override
    public void cacheDelete(T entity) {
        CacheCompositeKey key = compositeKeyExtractor.apply(entity);
        ID id = idExtractor.apply(entity);
        writeSubmitter.submit(entityName, WriteTaskSubmitter.Op.DELETE, entity, id);
        Map<ID, T> bucket = cache.getIfPresent(key);
        if (bucket != null) {
            bucket.remove(id);
        }
    }

    /**
     * 返回 cacheInsert 写入用的「完整」缓存桶：已在缓存则直接返回；命中新号策略（确知库中无数据）
     * 则建空桶；否则 load-through 从库加载整组后再返回，最后由调用方把新记录 put 进去。
     * <p>
     * 关键：组合键缓存一旦在内存中存在某个 key 的桶，{@link #cacheLoad} 就视其为权威结果、不再回库。
     * 旧实现用 {@code computeIfAbsent} 凭空建出只含单条新记录的残缺桶，会让后续 cacheLoad 漏掉库里
     * 的历史记录。业务通常先 cacheLoad 再 insert，此时桶已存在、不触发回库；只有「未加载就盲插」
     * 才会回库一次，而这恰恰是保证结果集完整所必需的。
     * <p>
     * cacheUpdate / cacheDelete 不走这里：它们对未加载的桶刻意保持 no-op（不凭空建桶），
     * 留待下次 cacheLoad 整组回库，行为与既有契约一致。
     */
    private Map<ID, T> bucketForWrite(CacheCompositeKey key) {
        Map<ID, T> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        if (newRolePolicy.skipLoad(key, roleIdExtractor)) {
            return cache.asMap().computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        }
        return cache.get(key, this::loadBucketFromDb);
    }

    /** 按组合键从数据库加载整组记录，构建主键索引桶。供 cacheLoad 与写入 load-through 共用。 */
    private Map<ID, T> loadBucketFromDb(CacheCompositeKey key) {
        Map<ID, T> bucket = new ConcurrentHashMap<>();
        List<T> loaded = dbLoader.apply(key);
        if (loaded != null) {
            for (T entity : loaded) {
                bucket.put(idExtractor.apply(entity), entity);
            }
        }
        return bucket;
    }

    @Override
    public void evict(CacheCompositeKey compositeKey) {
        cache.invalidate(compositeKey);
    }

    @Override
    public void evictAll() {
        cache.invalidateAll();
    }

    @Override
    public void warmUp(List<CacheCompositeKey> compositeKeys) {
        for (CacheCompositeKey key : compositeKeys) {
            List<T> loaded = dbLoader.apply(key);
            if (loaded != null && !loaded.isEmpty()) {
                Map<ID, T> bucket = new ConcurrentHashMap<>();
                for (T entity : loaded) {
                    ID id = idExtractor.apply(entity);
                    bucket.put(id, entity);
                }
                cache.put(key, bucket);
            }
        }
    }

    /** 获取当前缓存的组合键数量（用于监控） */
    public int size() {
        return (int) cache.estimatedSize();
    }

    private List<T> toList(Map<ID, T> bucket) {
        if (bucket == null || bucket.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(bucket.values());
    }
}
