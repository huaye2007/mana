package cn.managame.jpa.rdb.cache;

import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.cache.impl.AbstractUniqueCacheWrapper;
import cn.managame.jpa.cache.impl.UniqueCacheRepository;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import cn.managame.jpa.rdb.metadata.RdbDefaultValues;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import cn.managame.jpa.rdb.repository.DefaultRdbRepository;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class RdbUniqueCacheRepository<T, ID> extends AbstractUniqueCacheWrapper<T, ID>
        implements IRdbUniqueCacheRepository<T, ID> {

    private final DefaultRdbRepository<T, ID> delegate;

    /**
     * Canonical constructor used by the factory: the cache repository is built
     * (and possibly shared, e.g. {@code @Warmup}) by the caller.
     */
    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    UniqueCacheRepository<T, ID> cacheRepo,
                                    boolean asyncRoutingRequired) {
        super(metadata, cacheRepo, asyncRoutingRequired, "RDB");
        this.delegate = delegate;
    }

    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    CacheConfig config,
                                    WriteTaskSubmitter writeSubmitter) {
        this(metadata, delegate, config, writeSubmitter, metadata.logicalName(), false, NewRolePolicy.disabled());
    }

    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    CacheConfig config,
                                    WriteTaskSubmitter writeSubmitter,
                                    boolean asyncRoutingRequired) {
        this(metadata, delegate, config, writeSubmitter, metadata.logicalName(), asyncRoutingRequired,
                NewRolePolicy.disabled());
    }

    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    CacheConfig config,
                                    WriteTaskSubmitter writeSubmitter,
                                    boolean asyncRoutingRequired,
                                    NewRolePolicy newRolePolicy) {
        this(metadata, delegate, config, writeSubmitter, metadata.logicalName(), asyncRoutingRequired, newRolePolicy);
    }

    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    CacheConfig config,
                                    WriteTaskSubmitter writeSubmitter,
                                    String writeEntityName,
                                    boolean asyncRoutingRequired) {
        this(metadata, delegate, config, writeSubmitter, writeEntityName, asyncRoutingRequired,
                NewRolePolicy.disabled());
    }

    public RdbUniqueCacheRepository(RdbEntityMetadata metadata,
                                    DefaultRdbRepository<T, ID> delegate,
                                    CacheConfig config,
                                    WriteTaskSubmitter writeSubmitter,
                                    String writeEntityName,
                                    boolean asyncRoutingRequired,
                                    NewRolePolicy newRolePolicy) {
        this(metadata, delegate,
                buildCacheRepository(metadata, delegate, config, writeSubmitter, writeEntityName, newRolePolicy),
                asyncRoutingRequired);
    }

    @Override
    protected T loadById(ID id) {
        return delegate.findById(id);
    }

    @Override
    protected T loadById(ID id, Object routingKey) {
        return delegate.findById(id, routingKey);
    }

    @Override
    public void cacheInsert(T entity) {
        RdbDefaultValues.applyInsertDefaults((RdbEntityMetadata) metadata, entity);
        super.cacheInsert(entity);
    }

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

    @SuppressWarnings("unchecked")
    private static <T, ID> UniqueCacheRepository<T, ID> buildCacheRepository(RdbEntityMetadata metadata,
                                                                            DefaultRdbRepository<T, ID> delegate,
                                                                            CacheConfig config,
                                                                            WriteTaskSubmitter writeSubmitter,
                                                                            String writeEntityName,
                                                                            NewRolePolicy newRolePolicy) {
        Function<ID, T> dbLoader = delegate::findById;
        Function<T, ID> idExtractor = entity -> (ID) metadata.idField().accessor().get(entity);
        Supplier<List<T>> allLoader = delegate::findAll;
        return new UniqueCacheRepository<>(writeSubmitter, writeEntityName, dbLoader, idExtractor,
                allLoader, config, newRolePolicy);
    }
}
