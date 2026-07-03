package com.github.huaye2007.mana.jpa.core.bootstrap;

import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleListener;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadataResolver;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryFactory;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;

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
