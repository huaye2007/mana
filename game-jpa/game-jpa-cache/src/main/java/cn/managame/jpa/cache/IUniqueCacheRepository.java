package cn.managame.jpa.cache;

/**
 * 唯一键缓存接口（存储模型无关）。
 * <p>
 * 以实体主键（@Id）作为缓存key，每个key对应一条记录。
 * 适用于玩家基础数据、背包等以 roleId 为主键的场景。
 * <p>
 * Cache 类只负责内存缓存的读写，不直接操作数据库（cacheLoad 除外，需要穿透加载）。
 * cacheUpdate / cacheDelete 更新内存缓存后，将变更提交到合并缓冲区，
 * 由专门的 Flusher 负责合并并批量写入数据库。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface IUniqueCacheRepository<T, ID> {

    /** 按主键加载（走缓存，缓存未命中时穿透到数据库加载） */
    T cacheLoad(ID id);

    /** 新增实体，写入内存缓存，并提交异步 INSERT 写任务。 */
    void cacheInsert(T entity);

    /** 更新实体，更新内存缓存，变更提交到合并缓冲区 */
    void cacheUpdate(T entity);

    /** 按主键删除，移除缓存，变更提交到合并缓冲区 */
    void cacheDelete(ID id);

    /** 手动失效指定主键的缓存 */
    void evict(ID id);

    /** 清空该实体的所有缓存 */
    void evictAll();

    /**
     * 批量预热缓存。
     * 从数据库批量加载指定主键的实体并放入缓存，适用于服务器启动时预加载。
     *
     * @param ids 需要预热的主键列表
     */
    void warmUp(java.util.List<ID> ids);

    /**
     * 全量预热缓存。
     * 从数据库加载该实体的所有记录并放入缓存。
     * 注意：仅适用于数据量可控的实体（如配置表），大表慎用。
     */
    default boolean supportsWarmUpAll() {
        return false;
    }

    void warmUpAll();
}
