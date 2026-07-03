package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.IMultiCacheRepository;
import com.github.huaye2007.mana.jpa.docdb.repository.DocRepository;

/**
 * DocDB 组合键缓存 Repository 接口。
 * <p>
 * 同时继承 {@link DocRepository}（标准 CRUD）和 {@link IMultiCacheRepository}（组合键缓存操作）。
 * 实体字段通过 @CacheKey(order=N) 标注组合键。
 *
 * <pre>{@code
 * public interface MailRepository extends IDocMultiCacheRepository<Mail, Long> {
 * }
 * }</pre>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface IDocMultiCacheRepository<T, ID> extends DocRepository<T, ID>, IMultiCacheRepository<T, ID> {
}
