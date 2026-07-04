package cn.managame.jpa.cache.impl;

import cn.managame.jpa.cache.CacheCompositeKey;
import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiCacheRepositoryTest {

    @Test
    public void updateWritesEntityWhenCurrentCompositeKeyBucketContainsId() {
        WriteTaskSubmitter submitter = (entityName, op, entity, id) -> {
        };
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                submitter,
                "item",
                key -> Collections.emptyList(),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());

        Item item = new Item(1L, 10L, "old");
        repository.cacheInsert(item);
        Item updated = new Item(1L, 10L, "new");
        repository.cacheUpdate(updated);

        List<Item> current = repository.cacheLoad(CacheCompositeKey.of(10L));
        assertEquals(1, current.size());
        assertSame(updated, current.get(0));
    }

    @Test
    public void cacheInsertDoesNotMutateLocalBucketWhenSubmitFails() {
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                    throw new RejectedExecutionException("queue closed");
                },
                "item",
                key -> Collections.emptyList(),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());

        assertThrows(RejectedExecutionException.class, () -> repository.cacheInsert(new Item(1L, 10L, "new")));
        assertEquals(0, repository.size());
    }

    @Test
    public void cacheUpdateDoesNotMutateLocalBucketWhenSubmitFails() {
        AtomicBoolean rejectWrites = new AtomicBoolean();
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                    if (rejectWrites.get()) {
                        throw new RejectedExecutionException("queue closed");
                    }
                },
                "item",
                key -> Collections.emptyList(),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());
        Item existing = new Item(1L, 10L, "old");
        repository.cacheInsert(existing);
        rejectWrites.set(true);

        assertThrows(RejectedExecutionException.class, () -> repository.cacheUpdate(new Item(1L, 10L, "new")));
        List<Item> current = repository.cacheLoad(CacheCompositeKey.of(10L));
        assertEquals(1, current.size());
        assertSame(existing, current.get(0));
    }

    @Test
    public void cacheDeleteDoesNotMutateLocalBucketWhenSubmitFails() {
        AtomicBoolean rejectWrites = new AtomicBoolean();
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                    if (rejectWrites.get()) {
                        throw new RejectedExecutionException("queue closed");
                    }
                },
                "item",
                key -> Collections.emptyList(),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());
        Item existing = new Item(1L, 10L, "old");
        repository.cacheInsert(existing);
        rejectWrites.set(true);

        assertThrows(RejectedExecutionException.class, () -> repository.cacheDelete(existing));
        List<Item> current = repository.cacheLoad(CacheCompositeKey.of(10L));
        assertEquals(1, current.size());
        assertSame(existing, current.get(0));
    }

    @Test
    public void cacheInsertOnUnloadedKeyDoesNotMaskExistingDbRows() {
        // owner=10 在库里已有一条记录；对未加载的组合键直接 cacheInsert 第二条，
        // 不能凭空建出只含新记录的残缺桶，否则 cacheLoad 会漏掉库里的历史记录。
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                key -> List.of(new Item(1L, 10L, "existing")),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());

        repository.cacheInsert(new Item(2L, 10L, "inserted"));

        List<Item> current = repository.cacheLoad(CacheCompositeKey.of(10L));
        assertEquals(2, current.size());
        assertTrue(current.stream().anyMatch(i -> i.id == 1L), "existing DB row must remain visible");
        assertTrue(current.stream().anyMatch(i -> i.id == 2L), "inserted row must be visible");
    }

    @Test
    public void updateDoesNotCreateBucketWhenEntityIsNotCached() {
        AtomicInteger submitted = new AtomicInteger();
        WriteTaskSubmitter submitter = (entityName, op, entity, id) -> submitted.incrementAndGet();
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                submitter,
                "item",
                key -> Collections.emptyList(),
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults());

        repository.cacheUpdate(new Item(1L, 10L, "new"));

        assertEquals(0, repository.size());
        assertEquals(1, submitted.get());
    }

    @Test
    public void cacheLoadSkipsLoaderForCachedNewRoleDecision() {
        AtomicInteger detectorCalls = new AtomicInteger();
        AtomicInteger loadCount = new AtomicInteger();
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                key -> {
                    loadCount.incrementAndGet();
                    return List.of(new Item(1L, 10L, "loaded"));
                },
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults(),
                key -> key.part(0),
                NewRolePolicy.of(roleId -> {
                    detectorCalls.incrementAndGet();
                    return true;
                }, Duration.ofMinutes(5)));

        assertTrue(repository.cacheLoad(CacheCompositeKey.of(10L)).isEmpty());
        assertTrue(repository.cacheLoad(CacheCompositeKey.of(10L)).isEmpty());
        assertEquals(1, detectorCalls.get());
        assertEquals(0, loadCount.get());
    }

    @Test
    public void cacheLoadUsesLoaderWhenRoleIsNotNew() {
        AtomicInteger loadCount = new AtomicInteger();
        MultiCacheRepository<Item, Long> repository = new MultiCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                key -> {
                    loadCount.incrementAndGet();
                    return List.of(new Item(1L, 10L, "loaded"));
                },
                item -> item.id,
                item -> CacheCompositeKey.of(item.owner),
                CacheConfig.defaults(),
                key -> key.part(0),
                NewRolePolicy.of(roleId -> false, Duration.ofMinutes(5)));

        assertEquals(1, repository.cacheLoad(CacheCompositeKey.of(10L)).size());
        assertEquals(1, loadCount.get());
    }

    static class Item {
        private final long id;
        private final long owner;
        private final String name;

        Item(long id, long owner, String name) {
            this.id = id;
            this.owner = owner;
            this.name = name;
        }
    }
}
