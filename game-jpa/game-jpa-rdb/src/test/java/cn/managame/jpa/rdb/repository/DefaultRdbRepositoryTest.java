package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Id;
import cn.managame.jpa.rdb.annotation.ShardKey;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class DefaultRdbRepositoryTest {

    @Test
    public void findBySpecAndCountArePublicRepositoryOperations() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP);

        List<Player> result = repository.findBySpec(new RdbQuerySpec().eq("name", "a"));
        long count = repository.count(new RdbQuerySpec().eq("name", "a"));

        assertEquals(1, result.size());
        assertEquals(42, count);
        assertEquals(List.of("query", "count"), executor.operations);
    }

    @Test
    public void explicitInsertRoutingUsesProvidedRoutingKey() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.insert(new Player(1L, 3L, "a"), 9L);

        assertEquals("ds_9", executor.contexts.get(0).dataSourceName());
        assertEquals("player_9", executor.contexts.get(0).physicalTableName());
    }

    @Test
    public void findBySpecInfersRoutingKeyFromShardKeyCondition() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.findBySpec(new RdbQuerySpec().eq("serverId", 7L).eq("name", "a"));

        assertEquals("ds_7", executor.contexts.get(0).dataSourceName());
        assertEquals("player_7", executor.contexts.get(0).physicalTableName());
    }

    @Test
    public void countInfersRoutingKeyFromSingleValueShardKeyInCondition() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.count(new RdbQuerySpec().in("serverId", List.of(8L)));

        assertEquals("ds_8", executor.contexts.get(0).dataSourceName());
        assertEquals("player_8", executor.contexts.get(0).physicalTableName());
    }

    @Test
    public void findBySpecWithoutShardKeyConditionStillFailsForShardedEntity() {
        assertThrows(GameJpaException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
            DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                    metadata, new RecordingExecutor(), new LifecycleDispatcher(), MetricsCollector.NOOP,
                    new NumericRoutingStrategy());
    
            repository.findBySpec(new RdbQuerySpec().eq("name", "a"));
        });
    }

    @Test
    public void logRepositorySubmitsAppendWithExplicitRoutingKey() {
        CapturingAppendSubmitter submitter = new CapturingAppendSubmitter();
        RdbLogRepository<Player, Long> repository = new DefaultRdbLogRepository<>(submitter, "rdb:player");

        Player entity = new Player(1L, 3L, "a");
        repository.append(entity, 11L);

        assertEquals("rdb:player", submitter.entityName);
        assertSame(entity, submitter.entity);
        assertEquals(11L, submitter.routingKey);
    }

    @Test
    public void logRepositorySubmitsAppendWithoutRoutingKey() {
        CapturingAppendSubmitter submitter = new CapturingAppendSubmitter();
        RdbLogRepository<Player, Long> repository = new DefaultRdbLogRepository<>(submitter, "rdb:player");

        Player entity = new Player(2L, 4L, "b");
        repository.append(entity);

        assertEquals("rdb:player", submitter.entityName);
        assertSame(entity, submitter.entity);
        assertNull(submitter.routingKey);
    }

    private static final class CapturingAppendSubmitter implements WriteTaskSubmitter {
        private String entityName;
        private Object entity;
        private Object routingKey;

        @Override
        public void submit(String entityName, Op op, Object entity, Object id) {
        }

        @Override
        public void append(String entityName, Object entity) {
            this.entityName = entityName;
            this.entity = entity;
            this.routingKey = null;
        }

        @Override
        public void append(String entityName, Object entity, Object routingKey) {
            this.entityName = entityName;
            this.entity = entity;
            this.routingKey = routingKey;
        }
    }

    @Test
    public void deleteByIdFiresLifecycleWithLoadedEntity() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher();
        RecordingLifecycle listener = new RecordingLifecycle();
        lifecycle.addListener(listener);

        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, lifecycle, MetricsCollector.NOOP);

        repository.deleteById(1L);

        assertEquals(List.of("beforeDelete:loaded", "afterDelete:loaded"), listener.events);
        assertEquals(List.of("findById", "deleteById"), executor.operations);
    }

    @Test
    public void listenerExceptionPropagatesAndPreventsRepositoryOperation() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher();
        lifecycle.addListener(new LifecycleListener() {
            @Override
            public void beforeInsert(Object entity) {
                throw new IllegalStateException("blocked");
            }
        });

        DefaultRdbRepository<Player, Long> repository = new DefaultRdbRepository<>(
                metadata, executor, lifecycle, MetricsCollector.NOOP);

        try {
            repository.insert(new Player(1L, 1L, "a"));
            fail("Expected listener exception");
        } catch (IllegalStateException expected) {
            assertEquals("blocked", expected.getMessage());
        }
        assertTrue(executor.operations.isEmpty());
    }

    @Entity
    @Table(name = "player")
    static class Player {
        @Id
        @Column
        private long id;

        @ShardKey
        @Column
        private long serverId;

        @Column
        private String name;

        Player() {
        }

        Player(long id, long serverId, String name) {
            this.id = id;
            this.serverId = serverId;
            this.name = name;
        }
    }

    private static class NumericRoutingStrategy implements RoutingStrategy {
        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            return "ds_" + routingKey;
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            return logicalName + "_" + routingKey;
        }
    }

    private static class RecordingLifecycle implements LifecycleListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeDelete(Object entity) {
            events.add("beforeDelete:" + ((Player) entity).name);
        }

        @Override
        public void afterDelete(Object entity) {
            events.add("afterDelete:" + ((Player) entity).name);
        }
    }

    private static class RecordingExecutor implements RdbExecutor {
        private final List<String> operations = new ArrayList<>();
        private final List<ExecutorContext> contexts = new ArrayList<>();
        private final Player loaded = new Player(1L, 1L, "loaded");

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
            operations.add("findById");
            contexts.add(context);
            return (T) loaded;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context) {
            operations.add("findAll");
            contexts.add(context);
            return (List<T>) List.of(loaded);
        }

        @Override
        public void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
            operations.add("insert");
            contexts.add(context);
        }

        @Override
        public void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
            operations.add("update");
            contexts.add(context);
        }

        @Override
        public void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
            operations.add("upsert");
            contexts.add(context);
        }

        @Override
        public void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
            operations.add("deleteById");
            contexts.add(context);
        }

        @Override
        public void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context) {
            operations.add("batchDelete");
            contexts.add(context);
        }

        @Override
        public void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
            operations.add("batchInsert");
            contexts.add(context);
        }

        @Override
        public void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
            operations.add("batchUpdate");
            contexts.add(context);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            operations.add("query");
            contexts.add(context);
            return (List<T>) List.of(loaded);
        }

        @Override
        public long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            operations.add("count");
            contexts.add(context);
            return 42;
        }
    }
}
