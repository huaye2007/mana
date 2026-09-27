package cn.managame.data.mapper;

import cn.managame.data.meta.EntityMeta;
import cn.managame.data.key.GroupKey;
import java.util.List;

public interface EntityMapper {
    void initialize(EntityMeta meta);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKey groupKey);
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
