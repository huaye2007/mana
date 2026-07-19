package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.rdb.cache.RdbCacheModule;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
        AtomicBoolean closed = new AtomicBoolean();
        DataSource dataSource = closeableDataSource(closed);

        GameJpaContext context = new GameJpaBootstrap()
                .use(MysqlStorage.using(dataSource))
                .use(RdbCacheModule.defaults())
                .bootstrap(List.of());
        assertInstanceOf(MysqlRdbExecutor.class, context.get(RdbExecutor.class));

        context.close();

        assertTrue(closed.get());
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
}
