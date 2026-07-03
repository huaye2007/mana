package com.github.huaye2007.mana.jpa.docdb.repository;

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
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;

/**
 * Doc Repository 工厂。
 * 通过 {@link ComponentRegistry} 获取所有依赖。
 */
public class DocRepositoryFactory implements RepositoryFactory {

    public DocRepositoryFactory() {
    }

    @Override
    public boolean supports(Class<?> repositoryType) {
        return DocRepository.class.isAssignableFrom(repositoryType);
    }

    @Override
    public Object createRepository(Class<?> repositoryType, ComponentRegistry registry) {
        Class<?> entityType = RepositoryTypes.resolveEntityType(repositoryType, DocRepository.class);
        DocEntityMetadata metadata = registry.get(MetadataRegistry.class)
                .get(entityType, DocEntityMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Doc metadata found for entity: " + entityType.getName()));
        RoutingStrategy routingStrategy = resolveRoutingStrategy(registry, metadata);
        DocExecutor executor = registry.get(DocExecutor.class);
        configureConverters(registry, executor);
        return new DefaultDocRepository<>(metadata,
                executor,
                registry.get(LifecycleDispatcher.class),
                registry.get(MetricsCollector.class),
                routingStrategy,
                DataSourceBinding.resolveHomeDataSource(registry, metadata));
    }

    private void configureConverters(ComponentRegistry registry, DocExecutor executor) {
        if (executor instanceof TypeConverterAware aware) {
            aware.setTypeConverterRegistry(registry.get(TypeConverterRegistry.class));
        }
    }

    private RoutingStrategy resolveRoutingStrategy(ComponentRegistry registry, DocEntityMetadata metadata) {
        RoutingStrategyRegistry routingRegistry = registry.find(RoutingStrategyRegistry.class);
        if (routingRegistry != null) {
            return routingRegistry.resolve(metadata.entityType(), metadata.logicalName());
        }
        return registry.find(RoutingStrategy.class);
    }
}
