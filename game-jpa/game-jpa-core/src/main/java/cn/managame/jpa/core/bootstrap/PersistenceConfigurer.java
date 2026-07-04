package cn.managame.jpa.core.bootstrap;

import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.routing.RoutingStrategy;

public interface PersistenceConfigurer {

    PersistenceConfigurer addResolver(EntityMetadataResolver<?> resolver);

    PersistenceConfigurer addRepositoryFactory(RepositoryFactory factory);

    PersistenceConfigurer addLifecycleListener(LifecycleListener listener);

    default PersistenceConfigurer addBootstrapHook(BootstrapHook hook) {
        throw new UnsupportedOperationException("Bootstrap hooks are not supported by this configurer");
    }

    PersistenceConfigurer metricsCollector(MetricsCollector collector);

    PersistenceConfigurer routingStrategy(RoutingStrategy strategy);

    <T> PersistenceConfigurer registerComponent(Class<T> type, T component);
}
