package cn.managame.jpa.core.write;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.ShardRoutingResolver;

/**
 * 基于 {@code @ShardKey} + {@link RoutingStrategy} 的提交期路由器。
 * <p>
 * 复用 {@link ShardRoutingResolver} 的「路由键 → (dataSource, physicalTable)」解析，把原本
 * 在刷盘期每周期重算的分片分组前移到提交期：
 * <ul>
 *   <li>显式 routingKey 优先；</li>
 *   <li>否则从实体 {@code @ShardKey} 字段提取；</li>
 *   <li>实体为空（如 DELETE 未携带实体）时，仅当 {@code @ShardKey == @Id} 可用 id 推断，否则快速失败。</li>
 * </ul>
 * 非分片实体（未配 {@code @ShardKey} 或无 {@link RoutingStrategy}）直接返回 {@link WriteDestination#DEFAULT}。
 */
public final class ShardWriteRouter implements WriteRouter {

    private final ShardRoutingResolver resolver;
    private final String homeDataSource;

    public ShardWriteRouter(EntityMetadata metadata, RoutingStrategy routingStrategy, String modelLabel) {
        this(metadata, routingStrategy, metadata.dataSourceName(), modelLabel);
    }

    public ShardWriteRouter(EntityMetadata metadata, RoutingStrategy routingStrategy,
            String homeDataSource, String modelLabel) {
        this.homeDataSource = (homeDataSource == null || homeDataSource.isEmpty()) ? "default" : homeDataSource;
        this.resolver = new ShardRoutingResolver(metadata, routingStrategy, this.homeDataSource, modelLabel);
    }

    @Override
    public WriteDestination resolve(Object entity, Object id, Object explicitRoutingKey) {
        if (!resolver.isSharded()) {
            return WriteDestination.of(homeDataSource, null);
        }
        ExecutorContext ctx;
        if (explicitRoutingKey != null) {
            ctx = resolver.resolve(explicitRoutingKey);
        } else if (entity != null) {
            ctx = resolver.resolveFromEntity(entity);
        } else {
            ctx = resolver.resolveFromId(id);
        }
        return WriteDestination.of(ctx.dataSourceName(), ctx.physicalTableName());
    }
}
