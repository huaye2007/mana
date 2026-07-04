package cn.managame.jpa.docdb.repository;

import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.ShardRoutingResolver;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.query.DocQuerySpec;
import cn.managame.jpa.docdb.query.DocUpdateSpec;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Default {@link DocRepository} implementation.
 * <p>
 * Bridges the repository API to {@link DocExecutor}, lifecycle callbacks,
 * metrics, and optional collection routing.
 */
public class DefaultDocRepository<T, ID> implements DocRepository<T, ID> {

    private final DocEntityMetadata metadata;
    private final DocExecutor executor;
    private final LifecycleDispatcher lifecycle;
    private final MetricsCollector metrics;
    private final ShardRoutingResolver routing;

    public DefaultDocRepository(DocEntityMetadata metadata, DocExecutor executor,
                                LifecycleDispatcher lifecycle, MetricsCollector metrics) {
        this(metadata, executor, lifecycle, metrics, null);
    }

    public DefaultDocRepository(DocEntityMetadata metadata, DocExecutor executor,
                                LifecycleDispatcher lifecycle, MetricsCollector metrics,
                                RoutingStrategy routingStrategy) {
        this(metadata, executor, lifecycle, metrics, routingStrategy, metadata.dataSourceName());
    }

    public DefaultDocRepository(DocEntityMetadata metadata, DocExecutor executor,
                                LifecycleDispatcher lifecycle, MetricsCollector metrics,
                                RoutingStrategy routingStrategy, String homeDataSource) {
        this.metadata = metadata;
        this.executor = executor;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.routing = new ShardRoutingResolver(metadata, routingStrategy, homeDataSource, "DocDB");
    }

    @Override
    public T findById(ID id) {
        return findById(id, routing.resolveFromId(id));
    }

    @Override
    public T findById(ID id, Object routingKey) {
        return findById(id, routing.resolve(routingKey));
    }

    private T findById(ID id, ExecutorContext ctx) {
        return metrics.instrument("findById", metadata.logicalName(), () -> {
            T result = executor.findById(metadata, id, ctx);
            if (result != null) {
                lifecycle.fireAfterLoad(result);
            }
            return result;
        });
    }

    @Override
    public void insert(T entity) {
        metrics.instrument("insert", metadata.logicalName(), () -> {
            lifecycle.fireBeforeInsert(entity);
            executor.insert(metadata, entity, routing.resolveFromEntity(entity));
            lifecycle.fireAfterInsert(entity);
        });
    }

    @Override
    public void deleteById(ID id) {
        deleteById(id, routing.resolveFromId(id));
    }

    @Override
    public void deleteById(ID id, Object routingKey) {
        deleteById(id, routing.resolve(routingKey));
    }

    private void deleteById(ID id, ExecutorContext ctx) {
        metrics.instrument("deleteById", metadata.logicalName(), () -> {
            T deleted = loadForLifecycle(id, ctx);
            if (deleted != null) {
                lifecycle.fireBeforeDelete(deleted);
            }
            executor.deleteById(metadata, id, ctx);
            if (deleted != null) {
                lifecycle.fireAfterDelete(deleted);
            }
        });
    }

    @Override
    public List<T> findAll() {
        return metrics.instrument("findAll", metadata.logicalName(), () -> {
            routing.ensureUnsharded("findAll");
            List<T> result = executor.findAll(metadata, ExecutorContext.defaultContext());
            result.forEach(lifecycle::fireAfterLoad);
            return result;
        });
    }

    @Override
    public List<T> find(DocQuerySpec querySpec) {
        return find(querySpec, resolveContextFromQuery(querySpec, "find"));
    }

    @Override
    public List<T> find(DocQuerySpec querySpec, Object routingKey) {
        return find(querySpec, routing.resolve(routingKey));
    }

    private List<T> find(DocQuerySpec querySpec, ExecutorContext ctx) {
        return metrics.instrument("find", metadata.logicalName(), () -> {
            List<T> result = executor.find(metadata, querySpec, ctx);
            result.forEach(lifecycle::fireAfterLoad);
            return result;
        });
    }

    @Override
    public void update(ID id, DocUpdateSpec updateSpec) {
        update(id, updateSpec, routing.resolveFromId(id));
    }

    @Override
    public void update(ID id, DocUpdateSpec updateSpec, Object routingKey) {
        update(id, updateSpec, routing.resolve(routingKey));
    }

    private void update(ID id, DocUpdateSpec updateSpec, ExecutorContext ctx) {
        metrics.instrument("update", metadata.logicalName(), () -> {
            T before = loadForLifecycle(id, ctx);
            if (before != null) {
                lifecycle.fireBeforeUpdate(before);
            }
            executor.update(metadata, id, updateSpec, ctx);
            fireAfterPartialUpdate(id, ctx, before);
        });
    }

    // ---- Routing context helpers ----

    /**
     * Query APIs can infer a single-shard route from an equality filter on the
     * {@code @ShardKey} property, so callers do not need to pass routingKey
     * separately when the query already contains it.
     */
    private ExecutorContext resolveContextFromQuery(DocQuerySpec querySpec, String operation) {
        if (!routing.isSharded()) {
            return ExecutorContext.defaultContext();
        }
        return routing.resolveForQuery(extractRoutingKey(querySpec), operation);
    }

    private Object extractRoutingKey(DocQuerySpec querySpec) {
        String shardProperty = metadata.shardKeyField().propertyName();
        String shardDocumentField = metadata.shardKeyField().documentFieldName();
        Object routingKey = null;
        boolean found = false;
        for (DocQuerySpec.Filter filter : querySpec.filters()) {
            if (!shardProperty.equals(filter.field()) && !shardDocumentField.equals(filter.field())) {
                continue;
            }
            Object candidate = filterRoutingValue(filter);
            if (candidate == null) {
                continue;
            }
            if (found && !Objects.equals(routingKey, candidate)) {
                throw new GameJpaException("Conflicting DocDB routing values for @ShardKey property "
                        + shardProperty + " on entity " + metadata.entityType().getName());
            }
            routingKey = candidate;
            found = true;
        }
        return routingKey;
    }

    private Object filterRoutingValue(DocQuerySpec.Filter filter) {
        if (filter.op() == DocQuerySpec.FilterOp.EQ) {
            return filter.value();
        }
        if (filter.op() == DocQuerySpec.FilterOp.IN
                && filter.value() instanceof Collection<?> values
                && values.size() == 1) {
            return values.iterator().next();
        }
        return null;
    }

    private T loadForLifecycle(ID id, ExecutorContext ctx) {
        if (!lifecycle.hasListeners()) {
            return null;
        }
        return executor.findById(metadata, id, ctx);
    }

    private void fireAfterPartialUpdate(ID id, ExecutorContext ctx, T before) {
        if (before == null) {
            return;
        }
        T after = executor.findById(metadata, id, ctx);
        lifecycle.fireAfterUpdate(after != null ? after : before);
    }
}
