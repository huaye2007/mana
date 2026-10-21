package cn.managame.data.mapper;

import cn.managame.data.meta.EntityMeta;
import cn.managame.data.key.GroupKeys;
import java.util.List;

/**
 * Storage for state entities. Threads: loads come from whichever threads read repositories; the four batch
 * methods come from the persistence pipeline, which may call them for different entity types at once (one table
 * per save thread, D-WRITE-02) but never for one entity type from two threads at once. An implementation shared
 * by several entity types must therefore be thread-safe across types.
 */
public interface EntityMapper {
    /**
     * Prepares this mapping and brings storage structure in line with it, as far as {@code schema} allows.
     * Called once per entity type during GameDataBuilder.build, before any background resource exists, so a
     * structure failure throws out of build and takes nothing over.
     */
    void initialize(EntityMeta meta, SchemaMode schema);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKeys groupKey);
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
