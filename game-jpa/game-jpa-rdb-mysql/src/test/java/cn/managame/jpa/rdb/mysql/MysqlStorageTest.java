package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.registry.DataSourceBinding;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.cache.RdbCacheModule;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlStorageTest {

    @Test
    void registersExecutorWithoutImplicitSchemaChanges() {
        CapturingConfigurer configurer = new CapturingConfigurer();

        MysqlStorage.using(dataSource()).configure(configurer);

        assertInstanceOf(MysqlRdbExecutor.class, configurer.components.get(RdbExecutor.class));
        assertNull(configurer.hook);
    }

    @Test
    void updateSchemaAddsSynchronizationToTheSameStorageConfiguration() {
        CapturingConfigurer configurer = new CapturingConfigurer();

        MysqlStorage.using(dataSource()).updateSchema().configure(configurer);

        assertInstanceOf(MysqlRdbExecutor.class, configurer.components.get(RdbExecutor.class));
        assertNotNull(configurer.hook);
    }

    @Test
    void composesWithCacheExtensionAndOwnsExecutorLifecycle() {
        AtomicBoolean gameClosed = new AtomicBoolean();
        AtomicBoolean logClosed = new AtomicBoolean();

        GameJpaContext context = new GameJpaBootstrap()
                .use(MysqlStorage.using(closeableDataSource(gameClosed))
                        .addDataSource("log", closeableDataSource(logClosed)))
                .use(RdbCacheModule.defaults())
                .bootstrap(List.of());
        MysqlRdbExecutor executor = assertInstanceOf(
                MysqlRdbExecutor.class, context.get(RdbExecutor.class));
        assertEquals(Set.of("default", "log"), executor.dataSourceNames());

        context.close();

        assertTrue(gameClosed.get());
        assertTrue(logClosed.get());
    }

    @Test
    void updateSchemaSeparatesMetadataByResolvedHomeDataSource() {
        DataSource gameDataSource = dataSource();
        DataSource logDataSource = dataSource();
        DataSourceRegistry<DataSource> dataSources = new DataSourceRegistry<>();
        dataSources.registerDefault(gameDataSource);
        dataSources.register("log", logDataSource);
        MysqlStorage storage = MysqlStorage.using(dataSources).updateSchema();

        MetadataRegistry metadata = new MetadataRegistry();
        RdbEntityMetadataResolver resolver = new RdbEntityMetadataResolver();
        metadata.register(resolver.resolve(GameEntity.class));
        metadata.register(resolver.resolve(LogEntity.class));
        metadata.register(resolver.resolve(AnnotatedLogEntity.class));
        TestComponentRegistry components = new TestComponentRegistry();
        components.register(MetadataRegistry.class, metadata);
        components.register(DataSourceBinding.class,
                new DataSourceBinding().register(LogEntity.class, "log"));

        Map<String, MetadataRegistry> grouped = storage.resolveSchemaMetadata(components);

        assertEquals(Set.of(GameEntity.class), entityTypes(grouped.get("default")));
        assertEquals(Set.of(LogEntity.class, AnnotatedLogEntity.class),
                entityTypes(grouped.get("log")));
    }

    private static DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(
                MysqlStorageTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource closeableDataSource(AtomicBoolean closed) {
        return (DataSource) Proxy.newProxyInstance(
                MysqlStorageTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class, AutoCloseable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closed.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CapturingConfigurer implements PersistenceConfigurer {
        private final Map<Class<?>, Object> components = new HashMap<>();
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
            components.put(type, component);
            return this;
        }
    }

    private static Set<Class<?>> entityTypes(MetadataRegistry registry) {
        HashSet<Class<?>> result = new HashSet<>();
        registry.getByModel(ModelTypes.RDB).forEach(metadata -> result.add(metadata.entityType()));
        return Set.copyOf(result);
    }

    private static final class TestComponentRegistry implements ComponentRegistry {
        private final Map<Class<?>, Object> components = new HashMap<>();

        @Override
        public <T> void register(Class<T> type, T component) {
            components.put(type, component);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            return (T) components.get(type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T find(Class<T> type) {
            return (T) components.get(type);
        }
    }

    @Entity
    @Table(name = "game_entity")
    private static final class GameEntity {
        @Id
        @Column
        private long id;
    }

    @Entity
    @Table(name = "log_entity")
    private static final class LogEntity {
        @Id
        @Column
        private long id;
    }

    @Entity
    @Table(name = "annotated_log_entity", dataSource = "log")
    private static final class AnnotatedLogEntity {
        @Id
        @Column
        private long id;
    }
}
