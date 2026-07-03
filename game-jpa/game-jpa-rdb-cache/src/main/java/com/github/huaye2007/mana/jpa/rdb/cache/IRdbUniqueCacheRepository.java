package com.github.huaye2007.mana.jpa.rdb.cache;

import com.github.huaye2007.mana.jpa.cache.IUniqueCacheRepository;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepository;

/**
 * RDB 唯一键缓存 Repository 接口。
 * <p>
 * 同时继承 {@link RdbRepository}（标准CRUD）和 {@link IUniqueCacheRepository}（缓存操作）。
 * 业务 Repository 继承此接口即可获得 RDB + 主键缓存能力。
 *
 * <pre>{@code
 * public interface PlayerRepository extends IRdbUniqueCacheRepository<Player, Long> {
 * }
 * }</pre>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface IRdbUniqueCacheRepository<T, ID> extends RdbRepository<T, ID>, IUniqueCacheRepository<T, ID> {

    /**
     * Delete from cache and enqueue async delete with an explicit shard routing key.
     * Use this when {@code @ShardKey} is not the primary key and the entity may
     * not already be present in cache.
     */
    void cacheDelete(ID id, Object routingKey);

    /**
     * Load through cache with an explicit shard routing key.
     * Use this when {@code @ShardKey} is not the primary key.
     */
    T cacheLoad(ID id, Object routingKey);
}
