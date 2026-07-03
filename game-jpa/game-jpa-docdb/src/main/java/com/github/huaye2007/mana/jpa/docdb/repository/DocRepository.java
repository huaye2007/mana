package com.github.huaye2007.mana.jpa.docdb.repository;

import com.github.huaye2007.mana.jpa.core.repository.RepositoryMarker;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;

import java.util.List;

/**
 * Base repository for document entities.
 *
 * @param <T>  entity type
 * @param <ID> primary key type
 */
public interface DocRepository<T, ID> extends RepositoryMarker {

    T findById(ID id);

    /**
     * Find by id with an explicit routing key. Use this when the shard key is
     * not the id field.
     */
    default T findById(ID id, Object routingKey) {
        return findById(id);
    }

    void insert(T entity);

    void deleteById(ID id);

    /**
     * Delete by id with an explicit routing key. Use this when the shard key is
     * not the id field.
     */
    default void deleteById(ID id, Object routingKey) {
        deleteById(id);
    }

    List<T> findAll();

    List<T> find(DocQuerySpec querySpec);

    /**
     * Find documents on a routed collection. Use this when a query targets one
     * shard explicitly.
     */
    default List<T> find(DocQuerySpec querySpec, Object routingKey) {
        return find(querySpec);
    }

    /**
     * Apply a partial update by id.
     */
    void update(ID id, DocUpdateSpec updateSpec);

    /**
     * Apply a partial update by id with an explicit routing key.
     */
    default void update(ID id, DocUpdateSpec updateSpec, Object routingKey) {
        update(id, updateSpec);
    }
}
