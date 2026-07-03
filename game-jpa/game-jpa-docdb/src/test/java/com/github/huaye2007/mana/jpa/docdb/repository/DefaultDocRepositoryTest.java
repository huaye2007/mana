package com.github.huaye2007.mana.jpa.docdb.repository;

import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleDispatcher;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Field;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.docdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadataResolver;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultDocRepositoryTest {

    @Test
    public void explicitRoutingKeySupportsIdAndQueryOperationsWhenShardKeyDiffersFromId() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.findById(100L, 7L);
        repository.deleteById(100L, 7L);
        repository.find(new DocQuerySpec().eq("name", "a"), 7L);
        repository.update(100L, new DocUpdateSpec().set("name", "b"), 7L);

        assertEquals(List.of("ds_7", "ds_7", "ds_7", "ds_7"), executor.dataSources);
        assertEquals(List.of("player_7", "player_7", "player_7", "player_7"), executor.collections);
    }

    @Test
    public void findInfersRoutingKeyFromShardKeyFilter() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.find(new DocQuerySpec().eq("serverId", 9L).eq("name", "a"));

        assertEquals(List.of("ds_9"), executor.dataSources);
        assertEquals(List.of("player_9"), executor.collections);
    }

    @Test
    public void findInfersRoutingKeyFromSingleValueShardKeyInFilter() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.find(new DocQuerySpec().in("serverId", List.of(10L)));

        assertEquals(List.of("ds_10"), executor.dataSources);
        assertEquals(List.of("player_10"), executor.collections);
    }

    @Test
    public void findInfersRoutingKeyFromShardDocumentFieldName() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());

        repository.find(new DocQuerySpec().eq("server_id", 11L));

        assertEquals(List.of("ds_11"), executor.dataSources);
        assertEquals(List.of("player_11"), executor.collections);
    }

    @Test
    public void findWithoutShardKeyFilterStillFailsForShardedEntity() {
        assertThrows(GameJpaException.class, () -> {
            DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
            DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                    metadata, new RecordingExecutor(), new LifecycleDispatcher(), MetricsCollector.NOOP,
                    new NumericRoutingStrategy());
    
            repository.find(new DocQuerySpec().eq("name", "a"));
        });
    }

    @Test
    public void idOnlyOperationWithoutRoutingKeyFailsWhenShardKeyDiffersFromId() {
        assertThrows(com.github.huaye2007.mana.jpa.core.exception.GameJpaException.class, () -> {
            DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerDoc.class);
            DefaultDocRepository<PlayerDoc, Long> repository = new DefaultDocRepository<>(
                    metadata, new RecordingExecutor(), new LifecycleDispatcher(), MetricsCollector.NOOP,
                    new NumericRoutingStrategy());
    
            repository.findById(100L);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void factoryUsesRoutingStrategyRegistryForEntitySpecificStrategy() {
        MetadataRegistry metadataRegistry = new MetadataRegistry();
        metadataRegistry.register(new DocEntityMetadataResolver().resolve(PlayerDoc.class));

        RecordingExecutor executor = new RecordingExecutor();
        RoutingStrategyRegistry routingRegistry = new RoutingStrategyRegistry()
                .defaultStrategy(new PrefixRoutingStrategy("default"))
                .registerEntity(PlayerDoc.class, new PrefixRoutingStrategy("entity"));

        SimpleRegistry registry = new SimpleRegistry();
        registry.register(MetadataRegistry.class, metadataRegistry);
        registry.register(DocExecutor.class, executor);
        registry.register(LifecycleDispatcher.class, new LifecycleDispatcher());
        registry.register(MetricsCollector.class, MetricsCollector.NOOP);
        registry.register(RoutingStrategyRegistry.class, routingRegistry);

        DocRepository<PlayerDoc, Long> repository = (DocRepository<PlayerDoc, Long>)
                new DocRepositoryFactory().createRepository(PlayerDocRepository.class, registry);

        repository.findById(100L, 7L);

        assertEquals(List.of("entity_ds_7"), executor.dataSources);
        assertEquals(List.of("entity_player_7"), executor.collections);
    }

    @Document(collection = "player")
    static class PlayerDoc {
        @Id
        private long id;

        @ShardKey
        @Field(name = "server_id")
        private long serverId;

        private String name;
    }

    interface PlayerDocRepository extends DocRepository<PlayerDoc, Long> {
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

    private static class PrefixRoutingStrategy implements RoutingStrategy {
        private final String prefix;

        private PrefixRoutingStrategy(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            return prefix + "_ds_" + routingKey;
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            return prefix + "_" + logicalName + "_" + routingKey;
        }
    }

    private static class SimpleRegistry implements ComponentRegistry {
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
                throw new IllegalArgumentException("Missing component: " + type.getName());
            }
            return component;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T find(Class<T> type) {
            return (T) components.get(type);
        }
    }

    private static class RecordingExecutor implements DocExecutor {
        private final List<String> dataSources = new ArrayList<>();
        private final List<String> collections = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
            record(context);
            return (T) new PlayerDoc();
        }

        @Override
        public void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
            record(context);
        }

        @Override
        public void save(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
            record(context);
        }

        @Override
        public void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
            record(context);
        }

        @Override
        public <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context) {
            record(context);
            return List.of();
        }

        @Override
        public <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec, ExecutorContext context) {
            record(context);
            return List.of();
        }

        @Override
        public void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec, ExecutorContext context) {
            record(context);
        }

        private void record(ExecutorContext context) {
            dataSources.add(context.dataSourceName());
            collections.add(context.physicalTableName());
        }
    }
}
