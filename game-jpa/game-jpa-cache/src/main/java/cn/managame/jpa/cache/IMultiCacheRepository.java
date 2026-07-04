package cn.managame.jpa.cache;

import java.util.List;

/**
 * 组合键缓存接口（存储模型无关）。
 * <p>
 * 通过 @CacheKey(order=N) 注解标记实体字段，框架自动按 order 排序提取字段值组成缓存key，
 * 每个组合键对应多条记录。
 * <p>
 * Cache 类只负责内存缓存的读写，不直接操作数据库（cacheLoad 除外，需要穿透加载）。
 * cacheInsert / cacheUpdate / cacheDelete 更新内存缓存后，将变更提交到合并缓冲区，
 * 由专门的 Flusher 负责合并并批量写入数据库。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface IMultiCacheRepository<T, ID> {

    /** 按组合缓存键加载多条记录（走缓存，缓存未命中时穿透到数据库加载） */
    List<T> cacheLoad(CacheCompositeKey compositeKey);

    /** 新增实体，更新内存缓存，变更提交到合并缓冲区 */
    void cacheInsert(T entity);

    /** 更新实体，更新内存缓存，变更提交到合并缓冲区 */
    void cacheUpdate(T entity);

    /** 删除实体，更新内存缓存，变更提交到合并缓冲区 */
    void cacheDelete(T entity);

    /** 手动失效指定组合键的缓存 */
    void evict(CacheCompositeKey compositeKey);

    /** 清空该实体的所有组合键缓存 */
    void evictAll();

    /**
     * 批量预热缓存。
     * 按组合键列表从数据库加载数据并放入缓存，适用于服务器启动时预加载。
     *
     * @param compositeKeys 需要预热的组合键列表
     */
    void warmUp(java.util.List<CacheCompositeKey> compositeKeys);
}
