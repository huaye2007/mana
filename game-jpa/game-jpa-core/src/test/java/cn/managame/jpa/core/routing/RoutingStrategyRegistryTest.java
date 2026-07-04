package cn.managame.jpa.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class RoutingStrategyRegistryTest {

    @Test
    public void resolvesEntityBeforeLogicalNameBeforeDefault() {
        RoutingStrategy defaultStrategy = named("default");
        RoutingStrategy tableStrategy = named("table");
        RoutingStrategy entityStrategy = named("entity");

        RoutingStrategyRegistry registry = new RoutingStrategyRegistry()
                .defaultStrategy(defaultStrategy)
                .registerLogicalName("log", tableStrategy)
                .registerEntity(PlayerLog.class, entityStrategy);

        assertSame(entityStrategy, registry.resolve(PlayerLog.class, "log"));
        assertSame(tableStrategy, registry.resolve(OtherLog.class, "log"));
        assertSame(defaultStrategy, registry.resolve(OtherLog.class, "other"));
    }

    private static RoutingStrategy named(String name) {
        return new RoutingStrategy() {
            @Override
            public String resolveDataSource(String logicalName, Object routingKey) {
                return name;
            }

            @Override
            public String resolvePhysicalName(String logicalName, Object routingKey) {
                return name;
            }
        };
    }

    private static class PlayerLog {
    }

    private static class OtherLog {
    }
}
