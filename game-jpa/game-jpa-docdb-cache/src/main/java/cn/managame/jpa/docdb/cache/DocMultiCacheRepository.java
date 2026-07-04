package cn.managame.jpa.docdb.cache;

import cn.managame.jpa.cache.CacheCompositeKey;
import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.cache.impl.MultiCacheRepository;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.query.DocQuerySpec;
import cn.managame.jpa.docdb.query.DocUpdateSpec;
import cn.managame.jpa.docdb.repository.DefaultDocRepository;

import java.util.List;
import java.util.function.Function;

/**
 * DocDB 组合键缓存 Repository 实现。
 * <p>
 * 复用 {@link MultiCacheRepository} 的通用缓存逻辑，DB 操作委托给 delegate
 * （{@link DefaultDocRepository}）。缓存键含 @ShardKey 属性时，穿透查询的 eq 条件
 * 会被 delegate 自动推断为单分片路由，无需在这里显式解析。
 */
public class DocMultiCacheRepository<T, ID> implements IDocMultiCacheRepository<T, ID> {

    private final DefaultDocRepository<T, ID> delegate;
    private final MultiCacheRepository<T, ID> cacheRepo;

    @SuppressWarnings("unchecked")
    public DocMultiCacheRepository(DocEntityMetadata metadata,
                                   DefaultDocRepository<T, ID> delegate,
                                   DocCacheKeyMeta cacheKeyMeta,
                                   CacheConfig config,
                                   WriteTaskSubmitter writeSubmitter,
                                   String writeEntityName,
                                   NewRolePolicy newRolePolicy) {
        this.delegate = delegate;

        // 按组合键从数据库加载：@ShardKey 属性的 eq 条件由 delegate 自动路由
        Function<CacheCompositeKey, List<T>> dbLoader = compositeKey -> {
            DocQuerySpec spec = new DocQuerySpec();
            String[] properties = cacheKeyMeta.propertyNames();
            for (int i = 0; i < properties.length; i++) {
                spec.eq(properties[i], compositeKey.part(i));
            }
            return delegate.find(spec);
        };

        Function<T, ID> idExtractor = entity -> (ID) metadata.idField().accessor().get(entity);

        this.cacheRepo = new MultiCacheRepository<>(
                writeSubmitter, writeEntityName,
                dbLoader, idExtractor, cacheKeyMeta::extractKey, config,
                cacheKeyMeta.hasRoleId() ? cacheKeyMeta::extractRoleId : null, newRolePolicy);
    }

    // ==================== IMultiCacheRepository（委托给 cacheRepo） ====================

    @Override
    public List<T> cacheLoad(CacheCompositeKey compositeKey) {
        return cacheRepo.cacheLoad(compositeKey);
    }

    @Override
    public void cacheInsert(T entity) {
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

    // ==================== DocRepository（委托给 delegate） ====================

    @Override public T findById(ID id) { return delegate.findById(id); }
    @Override public T findById(ID id, Object routingKey) { return delegate.findById(id, routingKey); }
    @Override public void insert(T entity) { delegate.insert(entity); }
    @Override public void deleteById(ID id) { delegate.deleteById(id); }
    @Override public void deleteById(ID id, Object routingKey) { delegate.deleteById(id, routingKey); }
    @Override public List<T> findAll() { return delegate.findAll(); }
    @Override public List<T> find(DocQuerySpec querySpec) { return delegate.find(querySpec); }
    @Override public List<T> find(DocQuerySpec querySpec, Object routingKey) { return delegate.find(querySpec, routingKey); }
    @Override public void update(ID id, DocUpdateSpec updateSpec) { delegate.update(id, updateSpec); }
    @Override public void update(ID id, DocUpdateSpec updateSpec, Object routingKey) { delegate.update(id, updateSpec, routingKey); }
}
