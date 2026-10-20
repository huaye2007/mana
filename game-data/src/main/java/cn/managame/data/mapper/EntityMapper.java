package cn.managame.data.mapper;

import cn.managame.data.meta.EntityMeta;
import cn.managame.data.key.GroupKeys;
import java.util.List;

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
