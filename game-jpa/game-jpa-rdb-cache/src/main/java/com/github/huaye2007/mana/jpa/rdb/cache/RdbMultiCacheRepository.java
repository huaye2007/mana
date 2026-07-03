package com.github.huaye2007.mana.jpa.rdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheCompositeKey;
import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.IMultiCacheRepository;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.cache.impl.MultiCacheRepository;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbDefaultValues;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;
import com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec;
import com.github.huaye2007.mana.jpa.rdb.repository.DefaultRdbRepository;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepository;

import java.util.List;

/**
 * RDB 组合键缓存 Repository 实现。
 * <p>
 * 复用 {@link MultiCacheRepository} 的通用缓存逻辑，
 * DB 操作委托给 delegate（{@link DefaultRdbRepository}）。
 * <p>
 * 相比旧版使用 List + synchronized 的实现，新版使用 Map<ID, T> 按主键索引，
 * 更新/删除操作 O(1)，且线程安全由 ConcurrentHashMap 保证，无需显式 synchronized。
 */
public class RdbMultiCacheRepository<T, ID> implements IRdbMultiCacheRepository<T, ID> {

    private final RdbEntityMetadata metadata;
    private final DefaultRdbRepository<T, ID> delegate;
    private final MultiCacheRepository<T, ID> cacheRepo;
    private final RdbCacheKeyMeta cacheKeyMeta;

    public RdbMultiCacheRepository(ComponentRegistry registry,
                                    RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    RdbCacheKeyMeta cacheKeyMeta,
                                    CacheConfig config,
                                    com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter writeSubmitter) {
        this(registry, metadata, delegate, cacheKeyMeta, config, writeSubmitter, metadata.logicalName(),
                NewRolePolicy.disabled());
    }

    public RdbMultiCacheRepository(ComponentRegistry registry,
                                    RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    RdbCacheKeyMeta cacheKeyMeta,
                                    CacheConfig config,
                                    com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter writeSubmitter,
                                    NewRolePolicy newRolePolicy) {
        this(registry, metadata, delegate, cacheKeyMeta, config, writeSubmitter, metadata.logicalName(),
                newRolePolicy);
    }

    public RdbMultiCacheRepository(ComponentRegistry registry,
                                    RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    RdbCacheKeyMeta cacheKeyMeta,
                                    CacheConfig config,
                                    com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter writeSubmitter,
                                    String writeEntityName) {
        this(registry, metadata, delegate, cacheKeyMeta, config, writeSubmitter, writeEntityName,
                NewRolePolicy.disabled());
    }

    public RdbMultiCacheRepository(ComponentRegistry registry,
                                    RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    RdbCacheKeyMeta cacheKeyMeta,
                                    CacheConfig config,
                                    com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter writeSubmitter,
                                    String writeEntityName,
                                    NewRolePolicy newRolePolicy) {
        this.metadata = metadata;
        this.delegate = delegate;
        this.cacheKeyMeta = cacheKeyMeta;

        // 构建 dbLoader：按组合键从数据库加载
        java.util.function.Function<CacheCompositeKey, List<T>> dbLoader = compositeKey -> {
            com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec spec = new com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec();
            String[] properties = cacheKeyMeta.propertyNames();
            for (int i = 0; i < properties.length; i++) {
                spec.eq(properties[i], compositeKey.part(i));
            }
            Object routingKey = resolveRoutingKey(compositeKey);
            if (routingKey != null) {
                return delegate.findBySpec(spec, routingKey);
            }
            return delegate.findBySpec(spec);
        };

        // 构建 idExtractor：从实体提取主键
        java.util.function.Function<T, ID> idExtractor = entity ->
                (ID) metadata.idField().accessor().get(entity);

        // 构建 compositeKeyExtractor：从实体提取组合缓存键
        java.util.function.Function<T, CacheCompositeKey> compositeKeyExtractor = cacheKeyMeta::extractKey;

        this.cacheRepo = new MultiCacheRepository<>(
                writeSubmitter, writeEntityName,
                dbLoader, idExtractor, compositeKeyExtractor, config,
                cacheKeyMeta.hasRoleId() ? cacheKeyMeta::extractRoleId : null, newRolePolicy);
    }

    // ==================== IMultiCacheRepository（委托给 cacheRepo） ====================

    @Override
    public List<T> cacheLoad(CacheCompositeKey compositeKey) {
        return cacheRepo.cacheLoad(compositeKey);
    }

    @Override
    public void cacheInsert(T entity) {
        RdbDefaultValues.applyInsertDefaults(metadata, entity);
        cacheRepo.cacheInsert(entity);
    }

    @Override
    public void cacheUpdate(T entity) {
        cacheRepo.cacheUpdate(entity);
    }

    @Override
    public void cacheDelete(T entity) {
        cacheRepo.cacheDelete(entity);
    }

    @Override
    public void evict(CacheCompositeKey compositeKey) {
        cacheRepo.evict(compositeKey);
    }

    @Override
    public void evictAll() {
        cacheRepo.evictAll();
    }

    @Override
    public void warmUp(List<CacheCompositeKey> compositeKeys) {
        cacheRepo.warmUp(compositeKeys);
    }

    // ==================== RdbRepository（委托给 delegate） ====================

    @Override public T findById(ID id) { return delegate.findById(id); }
    @Override public T findById(ID id, Object routingKey) { return delegate.findById(id, routingKey); }
    @Override public List<T> findAll() { return delegate.findAll(); }
    @Override public List<T> findBySpec(RdbQuerySpec spec) { return delegate.findBySpec(spec); }
    @Override public List<T> findBySpec(RdbQuerySpec spec, Object routingKey) { return delegate.findBySpec(spec, routingKey); }
    @Override public long count(RdbQuerySpec spec) { return delegate.count(spec); }
    @Override public long count(RdbQuerySpec spec, Object routingKey) { return delegate.count(spec, routingKey); }
    @Override public void insert(T entity) { delegate.insert(entity); }
    @Override public void insert(T entity, Object routingKey) { delegate.insert(entity, routingKey); }
    @Override public void update(T entity) { delegate.update(entity); }
    @Override public void deleteById(ID id) { delegate.deleteById(id); }
    @Override public void deleteById(ID id, Object routingKey) { delegate.deleteById(id, routingKey); }
    @Override public void batchInsert(List<T> entities) { delegate.batchInsert(entities); }
    @Override public void batchInsert(List<T> entities, Object routingKey) { delegate.batchInsert(entities, routingKey); }
    @Override public void batchUpdate(List<T> entities) { delegate.batchUpdate(entities); }

    private Object resolveRoutingKey(CacheCompositeKey compositeKey) {
        if (!metadata.hasShardKey()) {
            return null;
        }
        String shardProperty = metadata.shardKeyField().propertyName();
        String[] properties = cacheKeyMeta.propertyNames();
        for (int i = 0; i < properties.length; i++) {
            if (properties[i].equals(shardProperty)) {
                return compositeKey.part(i);
            }
        }
        return null;
    }
}
