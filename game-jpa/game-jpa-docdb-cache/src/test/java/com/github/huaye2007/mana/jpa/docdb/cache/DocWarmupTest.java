package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.annotation.Warmup;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.docdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.starter.GameJpaBootstrap;
import com.github.huaye2007.mana.jpa.starter.GameJpaContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

public class DocWarmupTest {

    @Test
    public void bootstrapWarmsWarmupCacheAndReusesItForRepository() {
        WarmupItem item = new WarmupItem(1L, "one");
        RecordingDocExecutor executor = new RecordingDocExecutor(List.of(item, new WarmupItem(2L, "two")));

        GameJpaContext context = new GameJpaBootstrap()
                .install(DocdbCacheModule.withExecutor(executor))
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
        RecordingDocExecutor executor = new RecordingDocExecutor(List.of());

        assertThrows(GameJpaException.class, () -> new GameJpaBootstrap()
                .install(DocdbCacheModule.withExecutor(executor))
                .routingStrategy(new NumericRoutingStrategy())
                .bootstrap(List.of(ShardedWarmupItem.class)));
    }

    interface WarmupItemRepository extends IDocUniqueCacheRepository<WarmupItem, Long> {
    }

    @Warmup
    @Document(collection = "warmup_item")
    static class WarmupItem {
        @Id
        private long id;
        private String value;

        WarmupItem() {
        }

        WarmupItem(long id, String value) {
            this.id = id;
            this.value = value;
        }
    }

    @Warmup
    @Document(collection = "sharded_warmup_item")
    static class ShardedWarmupItem {
        @Id
        private long id;
        @ShardKey
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
}
