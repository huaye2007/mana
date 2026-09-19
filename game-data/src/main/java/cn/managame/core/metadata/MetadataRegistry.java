package cn.managame.core.metadata;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MetadataRegistry {
    private final ConcurrentMap<Class<?>, EntityMetadata<?>> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> EntityMetadata<T> get(Class<T> type) {
        return (EntityMetadata<T>) cache.computeIfAbsent(type, EntityMetadata::inspect);
    }
}
