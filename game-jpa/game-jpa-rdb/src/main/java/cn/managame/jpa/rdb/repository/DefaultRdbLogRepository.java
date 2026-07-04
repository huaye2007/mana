package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.write.WriteTaskSubmitter;

import java.util.List;

/**
 * 异步 append-only 日志仓储：把追加提交到异步写管道（追加通道），由异步刷盘调度器按物理表批量
 * {@code batchInsert} 落库。刻意不暴露查询/更新/删除/缓存 API。
 * <p>
 * 写回为最终一致：{@code append} 返回只代表任务已入队，<b>不</b>代表已持久化；进程崩溃可能丢失未刷盘日志，
 * 与其它异步写一致。路由键默认从实体 {@code @ShardKey} 字段提取，分表日志据此落到各物理表。
 */
public class DefaultRdbLogRepository<T, ID> implements RdbLogRepository<T, ID> {

    private final WriteTaskSubmitter submitter;
    private final String writeEntityName;

    public DefaultRdbLogRepository(WriteTaskSubmitter submitter, String writeEntityName) {
        this.submitter = submitter;
        this.writeEntityName = writeEntityName;
    }

    @Override
    public void append(T entity) {
        submitter.append(writeEntityName, entity);
    }

    @Override
    public void append(T entity, Object routingKey) {
        submitter.append(writeEntityName, entity, routingKey);
    }

    @Override
    public void appendBatch(List<T> entities) {
        for (T entity : entities) {
            submitter.append(writeEntityName, entity);
        }
    }

    @Override
    public void appendBatch(List<T> entities, Object routingKey) {
        for (T entity : entities) {
            submitter.append(writeEntityName, entity, routingKey);
        }
    }
}
