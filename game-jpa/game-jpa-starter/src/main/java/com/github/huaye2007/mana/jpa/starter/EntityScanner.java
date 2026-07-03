package com.github.huaye2007.mana.jpa.starter;

import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadataResolver;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;

import java.util.List;

/**
 * 实体扫描器。
 * 扫描指定包下的实体类，使用注册的 Resolver 解析元数据并注册到 MetadataRegistry。
 */
public class EntityScanner {

    private final MetadataRegistry registry;
    private final List<EntityMetadataResolver<?>> resolvers;

    public EntityScanner(MetadataRegistry registry, List<EntityMetadataResolver<?>> resolvers) {
        this.registry = registry;
        this.resolvers = resolvers;
    }

    /**
     * 扫描并注册实体类列表
     */
    public void scan(List<Class<?>> entityClasses) {
        for (Class<?> entityClass : entityClasses) {
            boolean resolved = false;
            for (EntityMetadataResolver<?> resolver : resolvers) {
                if (resolver.supports(entityClass)) {
                    EntityMetadata metadata = resolver.resolve(entityClass);
                    registry.register(metadata);
                    resolved = true;
                    break;
                }
            }
            if (!resolved) {
                throw new IllegalArgumentException("No EntityMetadataResolver supports entity: "
                        + entityClass.getName());
            }
        }
    }
}
