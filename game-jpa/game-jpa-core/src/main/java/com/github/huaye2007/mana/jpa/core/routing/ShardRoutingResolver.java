package com.github.huaye2007.mana.jpa.core.routing;

import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;

/**
 * 分片路由解析器。
 * <p>
 * 把 RDB / DocDB 仓储层原本各写一份的「路由键 → ExecutorContext」逻辑收敛到一处：
 * 从 id、实体、显式路由键解析目标 (dataSource, physicalName)，并对无法推断路由键的
 * 分片实体快速失败。查询条件里的路由键提取因 QuerySpec 类型不同仍由各仓储自行完成，
 * 提取出的键再交给本类 {@link #resolveForQuery} 处理。
 */
public final class ShardRoutingResolver {

    private final EntityMetadata metadata;
    private final RoutingStrategy routingStrategy;
    private final String homeDataSource;
    private final String modelLabel;

    public ShardRoutingResolver(EntityMetadata metadata, RoutingStrategy routingStrategy, String modelLabel) {
        this(metadata, routingStrategy, metadata.dataSourceName(), modelLabel);
    }

    /**
     * @param metadata        实体元数据（需暴露 idField/shardKeyField/hasShardKey）
     * @param routingStrategy 路由策略，未配置分库分表时为 {@code null}
     * @param homeDataSource  实体 home 数据源名（非分片读写的目标库），默认 {@code "default"}
     * @param modelLabel      模型标签（"RDB" / "DocDB"），仅用于错误信息
     */
    public ShardRoutingResolver(EntityMetadata metadata, RoutingStrategy routingStrategy,
            String homeDataSource, String modelLabel) {
        this.metadata = metadata;
        this.routingStrategy = routingStrategy;
        this.homeDataSource = (homeDataSource == null || homeDataSource.isEmpty()) ? "default" : homeDataSource;
        this.modelLabel = modelLabel;
    }

    /** 非分片实体的目标：home 数据源、逻辑表/集合（physical=null 由执行层回退到逻辑名）。 */
    private ExecutorContext homeContext() {
        return "default".equals(homeDataSource)
                ? ExecutorContext.defaultContext()
                : ExecutorContext.of(homeDataSource, null, null);
    }

    /** 该实体是否需要分片路由（配置了 @ShardKey 且存在路由策略）。 */
    public boolean isSharded() {
        return metadata.hasShardKey() && routingStrategy != null;
    }

    /**
     * 仅凭主键解析上下文：只有当 {@code @ShardKey} 落在主键字段上时才可行。
     */
    public ExecutorContext resolveFromId(Object id) {
        if (!isSharded()) {
            return homeContext();
        }
        if (metadata.shardKeyField() == metadata.idField()) {
            return resolve(id);
        }
        throw missingRoutingKey("id-only operation");
    }

    /**
     * 从实体中提取 {@code @ShardKey} 字段值并解析上下文。
     */
    public ExecutorContext resolveFromEntity(Object entity) {
        if (!isSharded()) {
            return homeContext();
        }
        Object routingKey = metadata.shardKeyField().accessor().get(entity);
        return resolve(routingKey);
    }

    /**
     * 从显式路由键解析上下文。
     */
    public ExecutorContext resolve(Object routingKey) {
        if (routingKey == null) {
            if (isSharded()) {
                throw missingRoutingKey("routed operation");
            }
            return homeContext();
        }
        if (routingStrategy == null) {
            return homeContext();
        }
        String logicalName = metadata.logicalName();
        String dsName = routingStrategy.resolveDataSource(logicalName, routingKey);
        String physicalName = routingStrategy.resolvePhysicalName(logicalName, routingKey);
        return ExecutorContext.of(dsName, routingKey, physicalName);
    }

    /**
     * 从查询条件里提取出的路由键解析上下文：分片实体若提取不到键则快速失败。
     */
    public ExecutorContext resolveForQuery(Object routingKey, String operation) {
        if (!isSharded()) {
            return homeContext();
        }
        if (routingKey == null) {
            throw missingRoutingKey(operation);
        }
        return resolve(routingKey);
    }

    /**
     * 校验该操作不依赖分片路由（如 findAll），分片实体直接拒绝。
     */
    public void ensureUnsharded(String operation) {
        if (isSharded()) {
            throw missingRoutingKey(operation);
        }
    }

    public GameJpaException missingRoutingKey(String operation) {
        return new GameJpaException(modelLabel + " " + operation
                + " requires explicit routingKey for sharded entity "
                + metadata.entityType().getName() + " because @ShardKey is not @Id");
    }
}
