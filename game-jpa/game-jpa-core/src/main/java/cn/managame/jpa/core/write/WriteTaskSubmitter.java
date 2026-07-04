package cn.managame.jpa.core.write;

/**
 * 异步写入任务提交器 SPI。
 * <p>
 * 缓存层通过此接口提交变更，无需直接依赖异步写入的具体实现（AsyncWriteQueue），
 * 实现缓存层与异步写入层的解耦。
 * <p>
 * 默认实现：{@code cn.managame.jpa.async.AsyncWriteQueue}。
 */
public interface WriteTaskSubmitter {

    /**
     * 提交合并通道（实体缓存）的变更操作。同一 {@code entityName + id} 的多次操作会合并为最终状态。
     *
     * @param entityName 内部写实体名（用于通道查找、提交期路由和落库器分发）
     * @param op         操作类型
     * @param entity     实体对象（DELETE 操作可为 null）
     * @param id         实体主键
     */
    void submit(String entityName, Op op, Object entity, Object id);

    /**
     * 提交追加通道（日志等 append-only）的一条记录，路由键从实体 {@code @ShardKey} 字段提取。
     * <p>
     * 仅追加、不按 id 合并；崩溃可能丢失未刷盘记录，语义与其它异步写一致。
     * 默认实现抛出 {@link UnsupportedOperationException}，由 {@code AsyncWriteQueue} 提供真实实现。
     */
    default void append(String entityName, Object entity) {
        throw new UnsupportedOperationException("append not supported by " + getClass().getName());
    }

    /**
     * 提交追加通道的一条记录，使用显式路由键（整条按该键路由）。
     */
    default void append(String entityName, Object entity, Object routingKey) {
        throw new UnsupportedOperationException("append not supported by " + getClass().getName());
    }

    /**
     * 操作类型枚举。
     * 独立于 {@link WriteTask.Op}，兼容只需要提交写任务的缓存层调用。
     * INSERT 和 UPDATE 进入异步队列后会合并为内部 SAVE 最终状态。
     */
    enum Op {
        INSERT, UPDATE, DELETE
    }
}
