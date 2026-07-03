package com.github.huaye2007.mana.jpa.rdb.repository;

import com.github.huaye2007.mana.jpa.core.repository.RepositoryMarker;
import com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec;

import java.util.List;

/**
 * Base repository for relational entities.
 *
 * @param <T>  entity type
 * @param <ID> primary key type
 */
public interface RdbRepository<T, ID> extends RepositoryMarker {

    T findById(ID id);

    /**
     * Find by primary key with an explicit routing key. Use this when the shard
     * key is not the primary key.
     */
    default T findById(ID id, Object routingKey) {
        return findById(id);
    }

    List<T> findAll();

    List<T> findBySpec(RdbQuerySpec spec);

    /**
     * Query with an explicit routing key. Use this when the shard key is not the
     * primary key and the query targets one shard.
     */
    default List<T> findBySpec(RdbQuerySpec spec, Object routingKey) {
        return findBySpec(spec);
    }

    long count(RdbQuerySpec spec);

    /**
     * Count with an explicit routing key. Use this when the shard key is not the
     * primary key and the query targets one shard.
     */
    default long count(RdbQuerySpec spec, Object routingKey) {
        return count(spec);
    }

    void insert(T entity);

    /**
     * Insert with an explicit routing key. This is useful for append/log paths
     * whose physical table is chosen by a value outside the primary key.
     */
    default void insert(T entity, Object routingKey) {
        insert(entity);
    }

    void update(T entity);

    void deleteById(ID id);

    /**
     * Delete by primary key with an explicit routing key. Use this when the shard
     * key is not the primary key.
     */
    default void deleteById(ID id, Object routingKey) {
        deleteById(id);
    }

    void batchInsert(List<T> entities);

    /**
     * Batch insert with an explicit routing key. All entities are written to the
     * same routed target.
     */
    default void batchInsert(List<T> entities, Object routingKey) {
        batchInsert(entities);
    }

    void batchUpdate(List<T> entities);
}
