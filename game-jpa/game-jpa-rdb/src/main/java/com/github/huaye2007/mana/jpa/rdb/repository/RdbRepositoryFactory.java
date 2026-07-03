package com.github.huaye2007.mana.jpa.rdb.repository;

import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.converter.TypeConverterAware;
import com.github.huaye2007.mana.jpa.core.converter.TypeConverterRegistry;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleDispatcher;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.registry.DataSourceBinding;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryFactory;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryTypes;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.rdb.executor.RdbExecutor;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;

/**
 * RDB Repository 工厂。
 * 通过 {@link ComponentRegistry} 获取所有依赖。
 */
public class RdbRepositoryFactory implements RepositoryFactory {

    public RdbRepositoryFactory() {
    }

    @Override
    public boolean supports(Class<?> repositoryType) {
        return RdbRepository.class.isAssignableFrom(repositoryType);
    }

    @Override
    public Object createRepository(Class<?> repositoryType, ComponentRegistry registry) {
        Class<?> entityType = RepositoryTypes.resolveEntityType(repositoryType, RdbRepository.class);
        RdbEntityMetadata metadata = registry.get(MetadataRegistry.class)
                .get(entityType, RdbEntityMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No RDB metadata found for entity: " + entityType.getName()));
        RdbExecutor executor = registry.get(RdbExecutor.class);
        configureConverters(registry, executor);
        return new DefaultRdbRepository<>(metadata,
                executor,
                registry.get(LifecycleDispatcher.class),
                registry.get(MetricsCollector.class),
                resolveRoutingStrategy(registry, metadata),
                DataSourceBinding.resolveHomeDataSource(registry, metadata));
    }

    private void configureConverters(ComponentRegistry registry, RdbExecutor executor) {
        if (executor instanceof TypeConverterAware aware) {
            aware.setTypeConverterRegistry(registry.get(TypeConverterRegistry.class));
        }
    }

    private RoutingStrategy resolveRoutingStrategy(ComponentRegistry registry, RdbEntityMetadata metadata) {
        RoutingStrategyRegistry routingRegistry = registry.find(RoutingStrategyRegistry.class);
        if (routingRegistry != null) {
            return routingRegistry.resolve(metadata.entityType(), metadata.logicalName());
        }
        return registry.find(RoutingStrategy.class);
    }

}
