package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleDispatcher;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.write.ShardWriteRouter;
import com.github.huaye2007.mana.jpa.core.write.WriteDestination;
import com.github.huaye2007.mana.jpa.core.write.WriteTask;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;
import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.docdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadataResolver;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;
import com.github.huaye2007.mana.jpa.docdb.repository.DefaultDocRepository;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class DocBatchExecutorTest {

    @Test
    public void routerResolvesShardFromEntityForDelete() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "DocDB");

        PlayerDoc entity = new PlayerDoc();
        entity.id = 100L;
        entity.serverId = 7L;

        WriteDestination dest = router.resolve(entity, entity.id, null);

        assertEquals("ds_7", dest.dataSource());
        assertEquals("player_7", dest.physicalTable());
    }

    @Test
    public void deleteUsesBatchDeleteWithRoutedContext() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DocBatchExecutor batchExecutor = new DocBatchExecutor(metadata, executor, MetricsCollector.NOOP);
        ExecutorContext ctx = ExecutorContext.of("ds_7", null, "player_7");

        batchExecutor.flush(WriteTask.Op.DELETE,
                List.of(new WriteTask(metadata.logicalName(), WriteTask.Op.DELETE, null, 100L)), ctx);

        assertEquals(List.of("delete"), executor.operations);
        assertEquals(List.of("ds_7"), executor.dataSources);
        assertEquals(List.of("player_7"), executor.collections);
    }

    @Test
    public void saveUsesBatchSaveWithRoutedContext() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DocBatchExecutor batchExecutor = new DocBatchExecutor(metadata, executor, MetricsCollector.NOOP);

        PlayerDoc entity = new PlayerDoc();
        entity.id = 100L;
        entity.serverId = 8L;
        ExecutorContext ctx = ExecutorContext.of("ds_8", null, "player_8");

        batchExecutor.flush(WriteTask.Op.SAVE,
                List.of(new WriteTask(metadata.logicalName(), WriteTask.Op.SAVE, entity, entity.id)), ctx);

        assertEquals(List.of("save"), executor.operations);
        assertEquals(List.of("ds_8"), executor.dataSources);
        assertEquals(List.of("player_8"), executor.collections);
    }

    @Test
    public void routerFailsWithoutEntityWhenShardKeyDiffersFromId() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "DocDB");

        assertThrows(GameJpaException.class, () -> router.resolve(null, 100L, null));
    }

    @Test
    public void uniqueCacheLoadWithRoutingKeyLoadsCorrectShard() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        PlayerDoc loaded = new PlayerDoc();
        loaded.id = 100L;
        loaded.serverId = 7L;
        executor.findResult = loaded;
        DefaultDocRepository<PlayerDoc, Long> delegate = new DefaultDocRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        DocUniqueCacheRepository<PlayerDoc, Long> repository = new DocUniqueCacheRepository<>(
                metadata, delegate, new CapturingSubmitter(), "docdb:player",
                id -> delegate.findById(id), entity -> entity.id, null, CacheConfig.defaults(), true);

        assertSame(loaded, repository.cacheLoad(100L, 7L));
        assertEquals("ds_7", executor.dataSources.get(executor.dataSources.size() - 1));
        assertEquals("player_7", executor.collections.get(executor.collections.size() - 1));
    }

    @Test
    public void uniqueCacheLoadFailsFastWhenShardKeyCannotBeInferred() {
        assertThrows(GameJpaException.class, () -> {
            DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
            RecordingExecutor executor = new RecordingExecutor();
            DefaultDocRepository<PlayerDoc, Long> delegate = new DefaultDocRepository<>(
                    metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
            DocUniqueCacheRepository<PlayerDoc, Long> repository = new DocUniqueCacheRepository<>(
                    metadata, delegate, new CapturingSubmitter(), "docdb:player",
                    id -> delegate.findById(id), entity -> entity.id, null, CacheConfig.defaults(), true);
    
            repository.cacheLoad(100L);
        });
    }

    @Document(collection = "player")
    static class PlayerDoc {
        @Id
        private long id;

        @ShardKey
        private long serverId;
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

    private static class RecordingExecutor implements DocExecutor {
        private final List<String> operations = new ArrayList<>();
        private final List<String> dataSources = new ArrayList<>();
        private final List<String> collections = new ArrayList<>();
        private Object findResult;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
            record("findById", context);
            return (T) findResult;
        }

        @Override
        public void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
            record("insert", context);
        }

        @Override
        public void save(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
            record("save", context);
        }

        @Override
        public void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
            record("delete", context);
        }

        @Override
        public <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context) {
            record("findAll", context);
            return List.of();
        }

        @Override
        public <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec, ExecutorContext context) {
            record("find", context);
            return List.of();
        }

        @Override
        public void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec, ExecutorContext context) {
            record("update", context);
        }

        private void record(String operation, ExecutorContext context) {
            operations.add(operation);
            dataSources.add(context.dataSourceName());
            collections.add(context.physicalTableName());
        }
    }

    private static final class CapturingSubmitter implements WriteTaskSubmitter {
        @Override
        public void submit(String entityName, Op op, Object entity, Object id) {
        }
    }
}
