package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.IUniqueCacheRepository;
import com.github.huaye2007.mana.jpa.docdb.repository.DocRepository;

public interface IDocUniqueCacheRepository<T, ID> extends DocRepository<T, ID>, IUniqueCacheRepository<T, ID> {

    /**
     * Delete from cache and enqueue async delete with an explicit shard routing key.
     */
    void cacheDelete(ID id, Object routingKey);

    /**
     * Load through cache with an explicit shard routing key.
     */
    T cacheLoad(ID id, Object routingKey);
}
