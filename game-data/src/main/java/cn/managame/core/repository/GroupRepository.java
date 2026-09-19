package cn.managame.core.repository;

import java.util.Map;

/**
 * Repository for entities loaded and cached as a complete group.
 *
 * ID is the entity primary-key type; group/map key types come from entity annotations.
 * A group must be loaded with getGroup(groupKey) before single-entity insert/update/delete operations.
 * Returns the cached map directly; the application must serialize access to each group,
 * including map iteration and entity changes. Use repository methods to persist mutations;
 * directly changing map entries does not submit database writes.
 * Map keys use @Id when no @MapKey is declared, the field value for one @MapKey,
 * or a colon-separated String in annotation order for multiple @MapKey fields.
 * The application must keep @Id, @GroupKey and @MapKey stable; the repository does not track original values.
 */
public interface GroupRepository<T, ID> {
    Map<Object, T> getGroup(Object groupKey);
    void insert(T entity);
    void update(T entity);
    void delete(T entity);
    void deleteGroup(Object groupKey);
    boolean resident();
}
