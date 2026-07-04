package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.repository.RepositoryMarker;

import java.util.List;

/**
 * Append-only repository for log-like relational entities.
 *
 * @param <T>  entity type
 * @param <ID> primary key type, used only for generic type resolution
 */
public interface RdbLogRepository<T, ID> extends RepositoryMarker {

    void append(T entity);

    void append(T entity, Object routingKey);

    void appendBatch(List<T> entities);

    void appendBatch(List<T> entities, Object routingKey);
}
