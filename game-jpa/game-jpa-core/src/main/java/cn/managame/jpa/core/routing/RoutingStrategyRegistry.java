package cn.managame.jpa.core.routing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves routing strategies at entity/table granularity.
 */
public class RoutingStrategyRegistry {

    private final Map<Class<?>, RoutingStrategy> entityStrategies = new ConcurrentHashMap<>();
    private final Map<String, RoutingStrategy> logicalNameStrategies = new ConcurrentHashMap<>();
    private volatile RoutingStrategy defaultStrategy;

    public RoutingStrategyRegistry defaultStrategy(RoutingStrategy strategy) {
        this.defaultStrategy = strategy;
        return this;
    }

    public RoutingStrategyRegistry registerEntity(Class<?> entityType, RoutingStrategy strategy) {
        if (entityType == null) {
            throw new IllegalArgumentException("entityType must not be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        entityStrategies.put(entityType, strategy);
        return this;
    }

    public RoutingStrategyRegistry registerLogicalName(String logicalName, RoutingStrategy strategy) {
        if (logicalName == null || logicalName.isEmpty()) {
            throw new IllegalArgumentException("logicalName must not be empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        logicalNameStrategies.put(logicalName, strategy);
        return this;
    }

    public RoutingStrategy resolve(Class<?> entityType, String logicalName) {
        if (entityType != null) {
            RoutingStrategy strategy = entityStrategies.get(entityType);
            if (strategy != null) {
                return strategy;
            }
        }
        if (logicalName != null) {
            RoutingStrategy strategy = logicalNameStrategies.get(logicalName);
            if (strategy != null) {
                return strategy;
            }
        }
        return defaultStrategy;
    }

    public RoutingStrategy defaultStrategy() {
        return defaultStrategy;
    }
}
