package com.github.huaye2007.mana.jpa.rdb.repository;

import com.github.huaye2007.mana.jpa.core.repository.RepositoryMarker;

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
