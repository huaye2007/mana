package cn.managame.jpa.rdb.executor;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;

import java.util.List;

/**
 * RDB 执行器接口。
 * 无类级泛型——同一个 Executor 实例服务所有 RDB 实体。
 */
public interface RdbExecutor {

    <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context);

    <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context);

    void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context);

    void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context);

    /**
     * 原子插入或更新操作（UPSERT）。
     * 用于消除 save() 方法中的 check-then-act 竞态。
     */
    void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context);

    void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context);

    void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context);

    void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context);

    void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context);

    default void batchUpsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        for (Object entity : entities) {
            upsert(metadata, entity, context);
        }
    }

    <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context);

    long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context);
}
