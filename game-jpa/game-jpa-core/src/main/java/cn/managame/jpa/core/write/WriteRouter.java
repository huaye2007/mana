package cn.managame.jpa.core.write;

/**
 * 提交期写路由 SPI。
 * <p>
 * 在写任务进入异步缓冲<b>之前</b>解析其物理目标 {@link WriteDestination}，让缓冲对象
 * 天然按 {@code (dataSource, physicalTable)} 组织、刷盘期不再重复路由。非分片实体返回
 * {@link WriteDestination#DEFAULT}（常量，几乎零开销）。
 */
@FunctionalInterface
public interface WriteRouter {

    /**
     * 解析写目标。
     *
     * @param entity            实体对象（DELETE 可能为 null）
     * @param id                实体主键
     * @param explicitRoutingKey 显式路由键（调用方提供时优先，否则从实体 {@code @ShardKey} 提取）
     * @return 物理写目标；无法为分片实体推断路由键时应快速失败（抛异常）
     */
    WriteDestination resolve(Object entity, Object id, Object explicitRoutingKey);

    /** 永远落默认目标的路由器，用于非分片实体。 */
    WriteRouter DEFAULT = (entity, id, explicitRoutingKey) -> WriteDestination.DEFAULT;
}
