package com.github.huaye2007.mana.jpa.rdb.mysql;

import com.github.huaye2007.mana.jpa.core.bootstrap.BootstrapHook;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceConfigurer;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleListener;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadataResolver;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryFactory;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.rdb.annotation.Column;
import com.github.huaye2007.mana.jpa.rdb.annotation.Entity;
import com.github.huaye2007.mana.jpa.rdb.annotation.Id;
import com.github.huaye2007.mana.jpa.rdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.rdb.annotation.Table;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MysqlSchemaModuleTest {

    @Test
    public void updateModuleSynchronizesAfterContextCreated() {
        TrackingSchemaGenerator generator = new TrackingSchemaGenerator();
        CapturingConfigurer configurer = new CapturingConfigurer();
        MysqlSchemaModule.withGenerator(generator, MysqlSchemaGenerator.Mode.UPDATE)
                .configure(configurer);
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(new RdbEntityMetadataResolver().resolve(Player.class));

        TestComponentRegistry components = new TestComponentRegistry();
        components.register(MetadataRegistry.class, registry);
        components.register(RoutingStrategyRegistry.class, new RoutingStrategyRegistry());
        configurer.hook.afterMetadataScan(registry);
        configurer.hook.afterContextCreated(components);

        assertSame(registry, generator.registry);
        assertEquals(MysqlSchemaGenerator.Mode.UPDATE, generator.mode);
        assertEquals(1, generator.rdbEntityCount);
    }

    @Test
    public void updateModuleRejectsShardedEntityWhenRoutingStrategyIsInstalled() {
        TrackingSchemaGenerator generator = new TrackingSchemaGenerator();
        CapturingConfigurer configurer = new CapturingConfigurer();
        MysqlSchemaModule.withGenerator(generator, MysqlSchemaGenerator.Mode.UPDATE)
                .configure(configurer);
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(new RdbEntityMetadataResolver().resolve(ShardedPlayer.class));
        TestComponentRegistry components = new TestComponentRegistry();
        components.register(MetadataRegistry.class, registry);
        components.register(RoutingStrategyRegistry.class,
                new RoutingStrategyRegistry().defaultStrategy(new TestRoutingStrategy()));

        GameJpaException expected = assertThrows(GameJpaException.class, () -> {
            configurer.hook.afterContextCreated(components);
        });

        assertTrue(expected.getMessage().contains("sharded"));
        assertEquals(0, generator.rdbEntityCount);
    }

    private static class TrackingSchemaGenerator extends MysqlSchemaGenerator {
        private MetadataRegistry registry;
        private Mode mode;
        private int rdbEntityCount;

        private TrackingSchemaGenerator() {
            super(null);
        }

        @Override
        public List<String> synchronize(MetadataRegistry registry, Mode mode) {
            this.registry = registry;
            this.mode = mode;
            this.rdbEntityCount = registry.getByModel(com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes.RDB).size();
            return List.of();
        }
    }

    private static class CapturingConfigurer implements PersistenceConfigurer {
        private BootstrapHook hook;

        @Override
        public PersistenceConfigurer addResolver(EntityMetadataResolver<?> resolver) {
            return this;
        }

        @Override
        public PersistenceConfigurer addRepositoryFactory(RepositoryFactory factory) {
            return this;
        }

        @Override
        public PersistenceConfigurer addLifecycleListener(LifecycleListener listener) {
            return this;
        }

        @Override
        public PersistenceConfigurer addBootstrapHook(BootstrapHook hook) {
            this.hook = hook;
            return this;
        }

        @Override
        public PersistenceConfigurer metricsCollector(MetricsCollector collector) {
            return this;
        }

        @Override
        public PersistenceConfigurer routingStrategy(RoutingStrategy strategy) {
            return this;
        }

        @Override
        public <T> PersistenceConfigurer registerComponent(Class<T> type, T component) {
            return this;
        }
    }

    private static class TestComponentRegistry implements ComponentRegistry {
        private final Map<Class<?>, Object> components = new ConcurrentHashMap<>();

        @Override
        public <T> void register(Class<T> type, T component) {
            components.put(type, component);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            T component = (T) components.get(type);
            if (component == null) {
                throw new IllegalArgumentException("Component not found: " + type.getName());
            }
            return component;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T find(Class<T> type) {
            return (T) components.get(type);
        }
    }

    private static class TestRoutingStrategy implements RoutingStrategy {
        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            return "default";
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            return logicalName + "_0";
        }
    }

    @Entity
    @Table(name = "schema_module_player")
    private static class Player {
        @Id
        @Column
        private long id;
    }

    @Entity
    @Table(name = "sharded_player")
    private static class ShardedPlayer {
        @Id
        @Column
        private long id;

        @ShardKey
        @Column
        private long roleId;
    }
}
