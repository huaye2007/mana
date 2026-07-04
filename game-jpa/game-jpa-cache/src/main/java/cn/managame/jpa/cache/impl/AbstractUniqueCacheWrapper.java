package cn.managame.jpa.cache.impl;

import cn.managame.jpa.cache.IUniqueCacheRepository;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.metadata.ReflectionUtils;

import java.util.List;

/**
 * 唯一键缓存包装层的公共基类（组合模式）。
 * <p>
 * RDB 与 DocDB 的缓存 Repository 原本各写一份「缓存读写 + 分片路由推断」逻辑，且一个用组合、
 * 一个用继承。此基类统一为组合：内部持有 {@link UniqueCacheRepository} 承担实际缓存读写，
 * 由各模型子类补充自身的 CRUD 透传（实现 {@link #loadById} 穿透加载）。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public abstract class AbstractUniqueCacheWrapper<T, ID> implements IUniqueCacheRepository<T, ID> {

    protected final EntityMetadata metadata;
    protected final UniqueCacheRepository<T, ID> cacheRepo;
    protected final boolean asyncRoutingRequired;
    private final String modelLabel;

    /**
     * @param metadata             实体元数据（需暴露 idField/shardKeyField/roleIdField）
     * @param cacheRepo            底层唯一键缓存
     * @param asyncRoutingRequired 异步写是否要求实体携带 @ShardKey（分片实体 cacheLoad(id) 无法推断时快速失败）
     * @param modelLabel           模型标签（"RDB" / "DocDB"），仅用于错误信息
     */
    protected AbstractUniqueCacheWrapper(EntityMetadata metadata,
                                         UniqueCacheRepository<T, ID> cacheRepo,
                                         boolean asyncRoutingRequired,
                                         String modelLabel) {
        this.metadata = metadata;
        this.cacheRepo = cacheRepo;
        this.asyncRoutingRequired = asyncRoutingRequired;
        this.modelLabel = modelLabel;
    }

    /** 缓存未命中时按主键穿透加载（路由由子类的 delegate 负责）。 */
    protected abstract T loadById(ID id);

    /** 缓存未命中时按主键 + 显式路由键穿透加载。 */
    protected abstract T loadById(ID id, Object routingKey);

    @Override
    public T cacheLoad(ID id) {
        T cached = cacheRepo.cachePeek(id);
        if (cached == null && requiresEntityForRouting()) {
            throw new GameJpaException(modelLabel + " cacheLoad(id) cannot infer @ShardKey for sharded entity "
                    + metadata.entityType().getName() + "; use cacheLoad(id, routingKey)");
        }
        return cacheRepo.cacheLoad(id, ignored -> loadById(id), roleLookupKey(id, null));
    }

    public T cacheLoad(ID id, Object routingKey) {
        return cacheRepo.cacheLoad(id, ignored -> loadById(id, routingKey), roleLookupKey(id, routingKey));
    }

    @Override
    public void cacheInsert(T entity) {
        cacheRepo.cacheInsert(entity);
    }

    @Override
    public void cacheUpdate(T entity) {
        cacheRepo.cacheUpdate(entity);
    }

    @Override
    public void cacheDelete(ID id) {
        T deletedEntity = cacheRepo.cachePeek(id);
        if (deletedEntity == null && requiresEntityForRouting()) {
            throw new GameJpaException(modelLabel + " cacheDelete(id) cannot infer @ShardKey because entity is not "
                    + "present in cache: " + metadata.entityType().getName());
        }
        cacheRepo.cacheDeleteWithEntity(id, deletedEntity);
    }

    public void cacheDelete(ID id, Object routingKey) {
        T deletedEntity = cacheRepo.cachePeek(id);
        if (deletedEntity == null) {
            deletedEntity = loadById(id, routingKey);
        }
        if (deletedEntity == null && routingKey != null && metadata.hasShardKey()) {
            deletedEntity = deleteRoutingEntity(id, routingKey);
        }
        cacheRepo.cacheDeleteWithEntity(id, deletedEntity);
    }

    @Override
    public void evict(ID id) {
        cacheRepo.evict(id);
    }

    @Override
    public void evictAll() {
        cacheRepo.evictAll();
    }

    @Override
    public void warmUp(List<ID> ids) {
        cacheRepo.warmUp(ids);
    }

    @Override
    public boolean supportsWarmUpAll() {
        return cacheRepo.supportsWarmUpAll();
    }

    @Override
    public void warmUpAll() {
        cacheRepo.warmUpAll();
    }

    /** 暴露底层缓存供子类在需要时复用（如 size 监控）。 */
    protected UniqueCacheRepository<T, ID> cacheRepo() {
        return cacheRepo;
    }

    protected boolean requiresEntityForRouting() {
        return asyncRoutingRequired && metadata.hasShardKey() && metadata.shardKeyField() != metadata.idField();
    }

    protected Object roleLookupKey(ID id, Object routingKey) {
        if (!metadata.hasRoleId()) {
            return null;
        }
        if (metadata.roleIdField() == metadata.idField()) {
            return id;
        }
        if (routingKey != null && metadata.roleIdField() == metadata.shardKeyField()) {
            return routingKey;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected T deleteRoutingEntity(ID id, Object routingKey) {
        try {
            Object entity = ReflectionUtils.newInstance(metadata.entityType());
            metadata.idField().accessor().set(entity, id);
            metadata.shardKeyField().accessor().set(entity, routingKey);
            return (T) entity;
        } catch (Exception e) {
            throw new GameJpaException("Failed to create " + modelLabel + " delete routing entity: "
                    + metadata.entityType().getName(), e);
        }
    }
}
