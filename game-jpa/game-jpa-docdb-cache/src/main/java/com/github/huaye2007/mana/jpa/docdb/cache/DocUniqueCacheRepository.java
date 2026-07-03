package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.cache.impl.AbstractUniqueCacheWrapper;
import com.github.huaye2007.mana.jpa.cache.impl.UniqueCacheRepository;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;
import com.github.huaye2007.mana.jpa.docdb.repository.DefaultDocRepository;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class DocUniqueCacheRepository<T, ID> extends AbstractUniqueCacheWrapper<T, ID>
        implements IDocUniqueCacheRepository<T, ID> {

    private final DefaultDocRepository<T, ID> delegate;

    public DocUniqueCacheRepository(DocEntityMetadata metadata,
                                    DefaultDocRepository<T, ID> delegate,
                                    WriteTaskSubmitter writeSubmitter,
                                    String entityName,
                                    Function<ID, T> dbLoader,
                                    Function<T, ID> idExtractor,
                                    Supplier<List<T>> allLoader,
                                    CacheConfig config) {
        this(metadata, delegate, writeSubmitter, entityName, dbLoader, idExtractor, allLoader, config, false);
    }

    public DocUniqueCacheRepository(DocEntityMetadata metadata,
                                    DefaultDocRepository<T, ID> delegate,
                                    WriteTaskSubmitter writeSubmitter,
                                    String entityName,
                                    Function<ID, T> dbLoader,
                                    Function<T, ID> idExtractor,
                                    Supplier<List<T>> allLoader,
                                    CacheConfig config,
                                    boolean asyncRoutingRequired) {
        this(metadata, delegate, writeSubmitter, entityName, dbLoader, idExtractor, allLoader, config,
                asyncRoutingRequired, NewRolePolicy.disabled());
    }

    public DocUniqueCacheRepository(DocEntityMetadata metadata,
                                    DefaultDocRepository<T, ID> delegate,
                                    WriteTaskSubmitter writeSubmitter,
                                    String entityName,
                                    Function<ID, T> dbLoader,
                                    Function<T, ID> idExtractor,
                                    Supplier<List<T>> allLoader,
                                    CacheConfig config,
                                    boolean asyncRoutingRequired,
                                    NewRolePolicy newRolePolicy) {
        super(metadata,
                new UniqueCacheRepository<>(writeSubmitter, entityName, dbLoader, idExtractor, allLoader,
                        config, newRolePolicy),
                asyncRoutingRequired, "DocDB");
        this.delegate = delegate;
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
    public T findById(ID id) {
        return delegate.findById(id);
    }

    @Override
    public T findById(ID id, Object routingKey) {
        return delegate.findById(id, routingKey);
    }

    @Override
    public void insert(T entity) {
        delegate.insert(entity);
    }

    @Override
    public void deleteById(ID id) {
        delegate.deleteById(id);
    }

    @Override
    public void deleteById(ID id, Object routingKey) {
        delegate.deleteById(id, routingKey);
    }

    @Override
    public List<T> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<T> find(DocQuerySpec querySpec) {
        return delegate.find(querySpec);
    }

    @Override
    public List<T> find(DocQuerySpec querySpec, Object routingKey) {
        return delegate.find(querySpec, routingKey);
    }

    @Override
    public void update(ID id, DocUpdateSpec updateSpec) {
        delegate.update(id, updateSpec);
    }

    @Override
    public void update(ID id, DocUpdateSpec updateSpec, Object routingKey) {
        delegate.update(id, updateSpec, routingKey);
    }
}
