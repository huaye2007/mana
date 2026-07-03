package com.github.huaye2007.mana.jpa.rdb.cache;

import com.github.huaye2007.mana.jpa.cache.IMultiCacheRepository;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepository;

/**
 * RDB 组合键缓存 Repository 接口。
 * <p>
 * 同时继承 {@link RdbRepository}（标准CRUD）和 {@link IMultiCacheRepository}（组合键缓存操作）。
 * 实体字段通过 @CacheKey(order=N) 标注组合键。
 *
 * <pre>{@code
 * public interface MailRepository extends IRdbMultiCacheRepository<Mail, Long> {
 * }
 * }</pre>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface IRdbMultiCacheRepository<T, ID> extends RdbRepository<T, ID>, IMultiCacheRepository<T, ID> {
}
