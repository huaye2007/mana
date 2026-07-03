package com.github.huaye2007.mana.jpa.rdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.annotation.Warmup;
import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.rdb.annotation.Column;
import com.github.huaye2007.mana.jpa.rdb.annotation.Entity;
import com.github.huaye2007.mana.jpa.rdb.annotation.Id;
import com.github.huaye2007.mana.jpa.rdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.rdb.annotation.Table;
import com.github.huaye2007.mana.jpa.rdb.executor.RdbExecutor;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;
import com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec;
import com.github.huaye2007.mana.jpa.starter.GameJpaBootstrap;
import com.github.huaye2007.mana.jpa.starter.GameJpaContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

public class RdbWarmupTest {

    @Test
    public void bootstrapWarmsWarmupCacheAndReusesItForRepository() {
        WarmupItem item = new WarmupItem(1L, "one");
        RecordingExecutor executor = new RecordingExecutor(List.of(item, new WarmupItem(2L, "two")));

        GameJpaContext context = new GameJpaBootstrap()
                .install(RdbCacheModule.withExecutor(executor)
                        .defaultConfig(CacheConfig.builder()
                                .maximumSize(1)
                                .expireAfterWrite(Duration.ZERO)
                                .build()))
                .bootstrap(List.of(WarmupItem.class));

        try {
            assertEquals(1, executor.findAllCalls);

            WarmupItemRepository repository = context.getRepository(WarmupItemRepository.class);
            assertSame(item, repository.cacheLoad(1L));
            assertEquals(0, executor.findByIdCalls);
        } finally {
            context.close();
        }
    }

    @Test
    public void bootstrapRejectsShardedWarmupCacheWhenRoutingIsConfigured() {
        RecordingExecutor executor = new RecordingExecutor(List.of());

        assertThrows(GameJpaException.class, () -> new GameJpaBootstrap()
                .install(RdbCacheModule.withExecutor(executor))
                .routingStrategy(new NumericRoutingStrategy())
                .bootstrap(List.of(ShardedWarmupItem.class)));
    }

    interface WarmupItemRepository extends IRdbUniqueCacheRepository<WarmupItem, Long> {
    }

    @Warmup
    @Entity
    @Table(name = "warmup_item")
    static class WarmupItem {
        @Id
        @Column
        private long id;
        @Column
        private String value;

        WarmupItem() {
        }

        WarmupItem(long id, String value) {
            this.id = id;
            this.value = value;
        }
    }

    @Warmup
    @Entity
    @Table(name = "sharded_warmup_item")
    static class ShardedWarmupItem {
        @Id
        @Column
        private long id;
        @ShardKey
        @Column
        private long roleId;
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

    private static class RecordingExecutor implements RdbExecutor {
        private final List<?> rows;
        private int findAllCalls;
        private int findByIdCalls;

        private RecordingExecutor(List<?> rows) {
            this.rows = rows;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
            findByIdCalls++;
            return (T) rows.stream()
                    .filter(row -> metadata.idField().accessor().get(row).equals(id))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context) {
            findAllCalls++;
            return (List<T>) rows;
        }

        @Override
        public void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        }

        @Override
        public void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context) {
        }

        @Override
        public void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        }

        @Override
        public void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        }

        @Override
        public <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            return List.of();
        }

        @Override
        public long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            return 0;
        }
    }
}
