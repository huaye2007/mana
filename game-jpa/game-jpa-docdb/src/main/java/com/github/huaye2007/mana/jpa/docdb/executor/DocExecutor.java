package com.github.huaye2007.mana.jpa.docdb.executor;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;

import java.util.List;

/**
 * 文档执行器接口。
 * 无类级泛型——同一个 Executor 实例服务所有 Doc 实体。
 */
public interface DocExecutor {

    <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context);

    void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context);

    void save(DocEntityMetadata metadata, Object entity, ExecutorContext context);

    void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context);

    <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context);

    <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec, ExecutorContext context);

    void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec, ExecutorContext context);

    /**
     * 批量 upsert：一次网络往返完成多条 save。
     * <p>
     * 默认实现退化为逐条 save 兼容老 Executor；高性能后端（如 Mongo）应覆盖为 bulkWrite。
     * 调用方必须保证所有 entities 路由到同一个 (dataSource, physicalCollection)。
     */
    default void batchSave(DocEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        for (Object entity : entities) {
            save(metadata, entity, context);
        }
    }

    /**
     * 批量按 id 删除：一次网络往返完成多条 delete。
     * <p>
     * 默认实现退化为逐条 deleteById 兼容老 Executor；高性能后端（如 Mongo）应覆盖为 bulkWrite。
     * 调用方必须保证所有 ids 路由到同一个 (dataSource, physicalCollection)。
     */
    default void batchDelete(DocEntityMetadata metadata, List<?> ids, ExecutorContext context) {
        for (Object id : ids) {
            deleteById(metadata, id, context);
        }
    }
}
