package cn.managame.jpa.starter;

import cn.managame.jpa.async.AsyncWriteQueue;
import cn.managame.jpa.async.FlushScheduler;
import cn.managame.jpa.async.FlushThreadMode;
import cn.managame.jpa.core.bootstrap.ModelType;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.registry.DataSourceCatalog;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.routing.RoutingStrategyRegistry;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteRouter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

public class GameJpaContextTest {

    @Test
    public void picksHighestPriorityRepositoryFactoryIndependentOfInstallOrder() {
        AsyncWriteQueue queue = new AsyncWriteQueue();
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(new StringFactory("low", 0), new StringFactory("high", 100)),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());

        try {
            assertEquals("high", context.getRepository(String.class));
        } finally {
            context.close();
        }
    }

    @Test
    public void adaptsFactoryImplementationToUserRepositoryInterface() {
        AsyncWriteQueue queue = new AsyncWriteQueue();
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(new UserRepositoryFactory()),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());

        try {
            UserRepository repository = context.getRepository(UserRepository.class);
            assertEquals("loaded-7", repository.load(7L));
            assertEquals("default-label", repository.defaultLabel());
        } finally {
            context.close();
        }
    }

    @Test
    public void failsFastWhenFactoryImplementationMissesRepositoryMethod() {
        AsyncWriteQueue queue = new AsyncWriteQueue();
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(new IncompleteRepositoryFactory()),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());

        try {
            context.getRepository(IncompleteRepository.class);
            fail("Expected repository contract validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("save"));
        } finally {
            context.close();
        }
    }

    @Test
    public void closeRejectsLaterAsyncWrites() {
        GameJpaContext context = new GameJpaBootstrap().bootstrap(List.of());
        AsyncWriteQueue queue = context.writeQueue();

        context.close();

        assertTrue(queue.isClosed());
        try {
            queue.submit("player", cn.managame.jpa.core.write.WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);
            fail("Expected queue rejection");
        } catch (RejectedExecutionException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    @Test
    public void directContextOwnsFlushSchedulerLifecycle() {
        AsyncWriteQueue queue = new AsyncWriteQueue();
        TrackingFlushScheduler scheduler = new TrackingFlushScheduler(queue);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());

        context.close();

        assertTrue(scheduler.closed);
        assertTrue(queue.isClosed());
    }

    @Test
    public void laterManagedResourcesCloseAfterFlushScheduler() {
        List<String> closeEvents = new ArrayList<>();
        AsyncWriteQueue queue = new AsyncWriteQueue();
        TrackingFlushScheduler scheduler = new TrackingFlushScheduler(queue, closeEvents);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());
        context.addManagedResource(() -> closeEvents.add("extra"));

        context.close();

        assertEquals(List.of("scheduler", "extra"), closeEvents);
    }

    @Test
    public void closeStopsSubmissionsBeforeSchedulerFinalFlush() {
        AsyncWriteQueue queue = new AsyncWriteQueue();
        RejectingFlushScheduler scheduler = new RejectingFlushScheduler(queue);
        GameJpaContext context = new GameJpaContext(
                new MetadataRegistry(),
                new TypeConverterRegistry(),
                new LifecycleDispatcher(),
                queue,
                scheduler,
                List.of(),
                MetricsCollector.NOOP,
                new RoutingStrategyRegistry());
        queue.register(new WriteChannel.Merge("player", WriteRouter.DEFAULT,
                (op, tasks, ctx) -> scheduler.flushed += tasks.size()));
        queue.submit("player", cn.managame.jpa.core.write.WriteTaskSubmitter.Op.UPDATE, "v1", 1L);

        context.close();

        assertTrue(scheduler.rejectedBeforeFlush);
        assertEquals(1, scheduler.flushed);
        assertTrue(queue.isClosed());
    }


    @Test
    public void bootstrapRejectsNonPositiveFlushInterval() {
        assertThrows(IllegalArgumentException.class, () -> {
            new GameJpaBootstrap().flushIntervalMillis(0);
        });
    }

    @Test
    public void bootstrapRejectsNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> {
            new GameJpaBootstrap().maxRetries(-1);
        });
    }

    @Test
    public void bootstrapRejectsNonPositiveFlushThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> {
            new GameJpaBootstrap().flushThreadCount(0);
        });
    }

    @Test
    public void bootstrapRejectsNonPositiveMaxFlushBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            new GameJpaBootstrap().maxFlushBatchSize(0);
        });
    }

    @Test
    public void bootstrapRejectsNonPositiveMaxPendingWriteTasks() {
        assertThrows(IllegalArgumentException.class, () -> {
            new GameJpaBootstrap().maxPendingWriteTasks(0);
        });
    }

    @Test
    public void bootstrapAppliesFlushBatchSize() {
        GameJpaContext context = new GameJpaBootstrap()
                .flushIntervalMillis(60_000)
                .flushThreadMode(FlushThreadMode.PLATFORM)
                .flushThreadCount(1)
                .maxFlushBatchSize(1)
                .bootstrap(List.of());
        List<Integer> batchSizes = new ArrayList<>();
        try {
            context.writeQueue().register(new WriteChannel.Merge("player", WriteRouter.DEFAULT,
                    (op, tasks, ctx) -> batchSizes.add(tasks.size())));
            context.writeQueue().submit("player", cn.managame.jpa.core.write.WriteTaskSubmitter.Op.UPDATE, "v1", 1L);
            context.writeQueue().submit("player", cn.managame.jpa.core.write.WriteTaskSubmitter.Op.UPDATE, "v2", 2L);

            context.flushScheduler().flush();

            assertEquals(List.of(1, 1), batchSizes);
        } finally {
            context.close();
        }
    }

    @Test
    public void bootstrapFailsFastWhenEntityHasNoResolver() {
        try {
            new GameJpaBootstrap().bootstrap(List.of(UnmappedEntity.class));
            fail("Expected missing resolver failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(UnmappedEntity.class.getName()));
        }
    }

    @Test
    public void bootstrapHooksRunAfterEntityMetadataScan() {
        List<Integer> registeredCounts = new ArrayList<>();
        GameJpaContext context = new GameJpaBootstrap()
                .addResolver(new TestEntityResolver())
                .addBootstrapHook(registry -> registeredCounts.add(registry.getAll().size()))
                .bootstrap(List.of(MappedEntity.class));

        try {
            assertEquals(List.of(1), registeredCounts);
            assertTrue(context.metadataRegistry().get(MappedEntity.class).isPresent());
        } finally {
            context.close();
        }
    }

    @Test
    public void bootstrapFailsFastWhenHomeDataSourceNotRegistered() {
        DataSourceCatalog catalog = () -> java.util.Set.of("default");
        ConfigurationException ex = assertThrows(ConfigurationException.class, () ->
                new GameJpaBootstrap()
                        .addResolver(new HomeDsResolver("log"))
                        .registerComponent(DataSourceCatalog.class, catalog)
                        .bootstrap(List.of(LogMappedEntity.class)));
        assertTrue(ex.getMessage().contains("log"));
    }

    @Test
    public void bootstrapPassesWhenHomeDataSourceRegistered() {
        DataSourceCatalog catalog = () -> java.util.Set.of("default", "log");
        GameJpaContext context = new GameJpaBootstrap()
                .addResolver(new HomeDsResolver("log"))
                .registerComponent(DataSourceCatalog.class, catalog)
                .bootstrap(List.of(LogMappedEntity.class));
        try {
            assertTrue(context.metadataRegistry().get(LogMappedEntity.class).isPresent());
        } finally {
            context.close();
        }
    }

    private static class LogMappedEntity {
    }

    private static class HomeDsResolver implements EntityMetadataResolver<EntityMetadata> {
        private final String dataSource;

        private HomeDsResolver(String dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean supports(Class<?> entityClass) {
            return entityClass == LogMappedEntity.class;
        }

        @Override
        public EntityMetadata resolve(Class<?> entityClass) {
            return new HomeDsMetadata(entityClass, dataSource);
        }
    }

    private record HomeDsMetadata(Class<?> entityType, String ds) implements EntityMetadata {
        @Override
        public ModelType modelType() {
            return ModelTypes.RDB;
        }

        @Override
        public String logicalName() {
            return "log_mapped";
        }

        @Override
        public String dataSourceName() {
            return ds;
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata idField() {
            return null;
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata shardKeyField() {
            return null;
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata roleIdField() {
            return null;
        }
    }

    private record StringFactory(String value, int priority) implements RepositoryFactory {
        @Override
        public boolean supports(Class<?> repositoryType) {
            return repositoryType == String.class;
        }

        @Override
        public Object createRepository(Class<?> repositoryType, cn.managame.jpa.core.context.ComponentRegistry registry) {
            return value;
        }
    }

    interface BaseUserRepository {
        String load(Long id);
    }

    interface UserRepository extends BaseUserRepository {
        default String defaultLabel() {
            return "default-label";
        }
    }

    interface IncompleteRepository extends BaseUserRepository {
        void save(String value);
    }

    static class BaseUserRepositoryImpl implements BaseUserRepository {
        @Override
        public String load(Long id) {
            return "loaded-" + id;
        }
    }

    private static class UserRepositoryFactory implements RepositoryFactory {
        @Override
        public boolean supports(Class<?> repositoryType) {
            return repositoryType == UserRepository.class;
        }

        @Override
        public Object createRepository(Class<?> repositoryType, cn.managame.jpa.core.context.ComponentRegistry registry) {
            return new BaseUserRepositoryImpl();
        }
    }

    private static class IncompleteRepositoryFactory implements RepositoryFactory {
        @Override
        public boolean supports(Class<?> repositoryType) {
            return repositoryType == IncompleteRepository.class;
        }

        @Override
        public Object createRepository(Class<?> repositoryType, cn.managame.jpa.core.context.ComponentRegistry registry) {
            return new BaseUserRepositoryImpl();
        }
    }

    private static class UnmappedEntity {
    }

    private static class MappedEntity {
    }

    private static class TestEntityResolver implements EntityMetadataResolver<EntityMetadata> {
        @Override
        public boolean supports(Class<?> entityClass) {
            return entityClass == MappedEntity.class;
        }

        @Override
        public EntityMetadata resolve(Class<?> entityClass) {
            return new TestEntityMetadata(entityClass);
        }
    }

    private record TestEntityMetadata(Class<?> entityType) implements EntityMetadata {
        @Override
        public ModelType modelType() {
            return ModelTypes.RDB;
        }

        @Override
        public String logicalName() {
            return "mapped";
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata idField() {
            return null;
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata shardKeyField() {
            return null;
        }

        @Override
        public cn.managame.jpa.core.metadata.FieldMetadata roleIdField() {
            return null;
        }
    }

    private static class TrackingFlushScheduler extends FlushScheduler {
        private final List<String> closeEvents;
        private boolean closed;

        private TrackingFlushScheduler(AsyncWriteQueue queue) {
            this(queue, new ArrayList<>());
        }

        private TrackingFlushScheduler(AsyncWriteQueue queue, List<String> closeEvents) {
            super(queue, 60_000, 1);
            this.closeEvents = closeEvents;
        }

        @Override
        public void close() {
            closed = true;
            closeEvents.add("scheduler");
            super.close();
        }
    }

    private static class RejectingFlushScheduler extends FlushScheduler {
        private final AsyncWriteQueue queue;
        private boolean rejectedBeforeFlush;
        private int flushed;

        private RejectingFlushScheduler(AsyncWriteQueue queue) {
            super(queue, 60_000, 1);
            this.queue = queue;
        }

        @Override
        public void close() {
            try {
                queue.submit("player", cn.managame.jpa.core.write.WriteTaskSubmitter.Op.UPDATE, "late", 2L);
            } catch (RejectedExecutionException expected) {
                rejectedBeforeFlush = true;
            }
            super.close();
        }
    }
}
