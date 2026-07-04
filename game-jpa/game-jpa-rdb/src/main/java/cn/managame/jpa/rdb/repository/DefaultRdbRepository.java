package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.ShardRoutingResolver;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbDefaultValues;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link RdbRepository} implementation.
 * <p>
 * Bridges the repository API to {@link RdbExecutor}, lifecycle callbacks,
 * metrics, and optional shard routing.
 */
public class DefaultRdbRepository<T, ID> implements RdbRepository<T, ID> {

    private final RdbEntityMetadata metadata;
    private final RdbExecutor executor;
    private final LifecycleDispatcher lifecycle;
    private final MetricsCollector metrics;
    private final ShardRoutingResolver routing;

    public DefaultRdbRepository(RdbEntityMetadata metadata, RdbExecutor executor,
            LifecycleDispatcher lifecycle, MetricsCollector metrics) {
        this(metadata, executor, lifecycle, metrics, null);
    }

    public DefaultRdbRepository(RdbEntityMetadata metadata, RdbExecutor executor,
            LifecycleDispatcher lifecycle, MetricsCollector metrics,
            RoutingStrategy routingStrategy) {
        // 默认以注解声明的 home 数据源为准；工厂会用绑定解析后的库名走下面的重载。
        this(metadata, executor, lifecycle, metrics, routingStrategy, metadata.dataSourceName());
    }

    public DefaultRdbRepository(RdbEntityMetadata metadata, RdbExecutor executor,
            LifecycleDispatcher lifecycle, MetricsCollector metrics,
            RoutingStrategy routingStrategy, String homeDataSource) {
        this.metadata = metadata;
        this.executor = executor;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.routing = new ShardRoutingResolver(metadata, routingStrategy, homeDataSource, "RDB");
    }

    @Override
    public T findById(ID id) {
        return findById(routing.resolveFromId(id), id);
    }

    @Override
    public T findById(ID id, Object routingKey) {
        return findById(routing.resolve(routingKey), id);
    }

    private T findById(ExecutorContext ctx, ID id) {
        return metrics.instrument("findById", metadata.logicalName(), () -> {
            T result = executor.findById(metadata, id, ctx);
            if (result != null) {
                lifecycle.fireAfterLoad(result);
            }
            return result;
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
    public void insert(T entity) {
        insert(entity, routing.resolveFromEntity(entity));
    }

    @Override
    public void insert(T entity, Object routingKey) {
        insert(entity, routing.resolve(routingKey));
    }

    private void insert(T entity, ExecutorContext ctx) {
        metrics.instrument("insert", metadata.logicalName(), () -> {
            RdbDefaultValues.applyInsertDefaults(metadata, entity);
            lifecycle.fireBeforeInsert(entity);
            executor.insert(metadata, entity, ctx);
            lifecycle.fireAfterInsert(entity);
        });
    }

    @Override
    public void update(T entity) {
        metrics.instrument("update", metadata.logicalName(), () -> {
            lifecycle.fireBeforeUpdate(entity);
            executor.update(metadata, entity, routing.resolveFromEntity(entity));
            lifecycle.fireAfterUpdate(entity);
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
            T deleted = loadForDeleteLifecycle(id, ctx);
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
    public void batchInsert(List<T> entities) {
        metrics.instrument("batchInsert", metadata.logicalName(), () -> {
            entities.forEach(entity -> RdbDefaultValues.applyInsertDefaults(metadata, entity));
            entities.forEach(lifecycle::fireBeforeInsert);
            for (Map.Entry<String, RoutedBatch<T>> entry : groupByRoute(entities).entrySet()) {
                RoutedBatch<T> batch = entry.getValue();
                executor.batchInsert(metadata, batch.entities(), batch.context());
            }
            entities.forEach(lifecycle::fireAfterInsert);
            metrics.recordCount("batchInsert", metadata.logicalName(), entities.size());
        });
    }

    @Override
    public void batchInsert(List<T> entities, Object routingKey) {
        ExecutorContext ctx = routing.resolve(routingKey);
        metrics.instrument("batchInsert", metadata.logicalName(), () -> {
            entities.forEach(entity -> RdbDefaultValues.applyInsertDefaults(metadata, entity));
            entities.forEach(lifecycle::fireBeforeInsert);
            executor.batchInsert(metadata, entities, ctx);
            entities.forEach(lifecycle::fireAfterInsert);
            metrics.recordCount("batchInsert", metadata.logicalName(), entities.size());
        });
    }

    @Override
    public void batchUpdate(List<T> entities) {
        metrics.instrument("batchUpdate", metadata.logicalName(), () -> {
            entities.forEach(lifecycle::fireBeforeUpdate);
            for (Map.Entry<String, RoutedBatch<T>> entry : groupByRoute(entities).entrySet()) {
                RoutedBatch<T> batch = entry.getValue();
                executor.batchUpdate(metadata, batch.entities(), batch.context());
            }
            entities.forEach(lifecycle::fireAfterUpdate);
            metrics.recordCount("batchUpdate", metadata.logicalName(), entities.size());
        });
    }

    @Override
    public List<T> findBySpec(RdbQuerySpec spec) {
        return findBySpec(spec, resolveContextFromSpec(spec, "findBySpec"));
    }

    @Override
    public List<T> findBySpec(RdbQuerySpec spec, Object routingKey) {
        return findBySpec(spec, routing.resolve(routingKey));
    }

    private List<T> findBySpec(RdbQuerySpec spec, ExecutorContext ctx) {
        return metrics.instrument("findBySpec", metadata.logicalName(), () -> {
            List<T> result = executor.query(metadata, spec, ctx);
            result.forEach(lifecycle::fireAfterLoad);
            return result;
        });
    }

    @Override
    public long count(RdbQuerySpec spec) {
        return count(spec, resolveContextFromSpec(spec, "count"));
    }

    @Override
    public long count(RdbQuerySpec spec, Object routingKey) {
        return count(spec, routing.resolve(routingKey));
    }

    private long count(RdbQuerySpec spec, ExecutorContext ctx) {
        return metrics.instrument("count", metadata.logicalName(),
                () -> executor.count(metadata, spec, ctx));
    }

    // ---- Routing context helpers ----

    /**
     * Query APIs can infer a single-shard route from an equality condition on
     * the {@code @ShardKey} property, so callers do not need to pass the same
     * value twice.
     */
    private ExecutorContext resolveContextFromSpec(RdbQuerySpec spec, String operation) {
        if (!routing.isSharded()) {
            return ExecutorContext.defaultContext();
        }
        return routing.resolveForQuery(extractRoutingKey(spec), operation);
    }

    private Object extractRoutingKey(RdbQuerySpec spec) {
        String shardProperty = metadata.shardKeyField().propertyName();
        Object routingKey = null;
        boolean found = false;
        for (RdbQuerySpec.Condition condition : spec.conditions()) {
            if (!shardProperty.equals(condition.property())) {
                continue;
            }
            Object candidate = conditionRoutingValue(condition);
            if (candidate == null) {
                continue;
            }
            if (found && !Objects.equals(routingKey, candidate)) {
                throw new GameJpaException("Conflicting RDB routing values for @ShardKey property "
                        + shardProperty + " on entity " + metadata.entityType().getName());
            }
            routingKey = candidate;
            found = true;
        }
        return routingKey;
    }

    private Object conditionRoutingValue(RdbQuerySpec.Condition condition) {
        if (condition.operator() == RdbQuerySpec.Operator.EQ) {
            return condition.value();
        }
        if (condition.operator() == RdbQuerySpec.Operator.IN
                && condition.value() instanceof Collection<?> values
                && values.size() == 1) {
            return values.iterator().next();
        }
        return null;
    }

    private Map<String, RoutedBatch<T>> groupByRoute(List<T> entities) {
        Map<String, RoutedBatch<T>> groups = new LinkedHashMap<>();
        for (T entity : entities) {
            ExecutorContext ctx = routing.resolveFromEntity(entity);
            String key = contextKey(ctx);
            groups.computeIfAbsent(key, ignored -> new RoutedBatch<>(ctx)).entities().add(entity);
        }
        return groups;
    }

    private String contextKey(ExecutorContext ctx) {
        return ctx.dataSourceName() + ":" + ctx.physicalTableName();
    }

    private T loadForDeleteLifecycle(ID id, ExecutorContext ctx) {
        if (!lifecycle.hasListeners()) {
            return null;
        }
        return executor.findById(metadata, id, ctx);
    }

    private static final class RoutedBatch<E> {
        private final ExecutorContext context;
        private final List<E> entities = new java.util.ArrayList<>();

        private RoutedBatch(ExecutorContext context) {
            this.context = context;
        }

        private ExecutorContext context() {
            return context;
        }

        private List<E> entities() {
            return entities;
        }
    }
}
