package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.converter.TypeConverterAware;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.registry.DataSourceBinding;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.repository.RepositoryTypes;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.RoutingStrategyRegistry;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;

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
