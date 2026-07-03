package com.github.huaye2007.mana.jpa.cache.impl;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UniqueCacheRepositoryTest {

    @Test
    public void cacheInsertStoresEntityAndSubmitsInsertTask() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                submitter,
                "item",
                id -> null,
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        Item item = new Item(1L);
        repository.cacheInsert(item);

        assertSame(item, repository.cacheLoad(1L));
        assertEquals(WriteTaskSubmitter.Op.INSERT, submitter.op);
        assertSame(item, submitter.entity);
        assertEquals(1L, submitter.id);
    }

    @Test
    public void cacheInsertDoesNotMutateLocalCacheWhenSubmitFails() {
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                    throw new RejectedExecutionException("queue closed");
                },
                "item",
                id -> null,
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        Item item = new Item(9L);

        assertThrows(RejectedExecutionException.class, () -> repository.cacheInsert(item));
        assertNull(repository.cacheLoad(9L));
    }

    @Test
    public void cacheUpdateDoesNotMutateLocalCacheWhenSubmitFails() {
        AtomicBoolean rejectWrites = new AtomicBoolean();
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                    if (rejectWrites.get()) {
                        throw new RejectedExecutionException("queue closed");
                    }
                },
                "item",
                id -> null,
                item -> item.id,
                List::of,
                CacheConfig.defaults());
        Item existing = new Item(1L);
        repository.cacheInsert(existing);
        rejectWrites.set(true);

        Item updated = new Item(1L);

        assertThrows(RejectedExecutionException.class, () -> repository.cacheUpdate(updated));
        assertSame(existing, repository.cacheLoad(1L));
    }

    @Test
    public void cacheDeleteDoesNotMarkDeletedWhenSubmitFails() {
        AtomicBoolean rejectWrites = new AtomicBoolean();
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                    if (rejectWrites.get()) {
                        throw new RejectedExecutionException("queue closed");
                    }
                },
                "item",
                id -> null,
                item -> item.id,
                List::of,
                CacheConfig.defaults());
        Item existing = new Item(1L);
        repository.cacheInsert(existing);
        rejectWrites.set(true);

        assertThrows(RejectedExecutionException.class, () -> repository.cacheDelete(1L));
        assertSame(existing, repository.cacheLoad(1L));
    }

    @Test
    public void cacheLoadUsesStorageAgnosticLoader() {
        AtomicInteger loadCount = new AtomicInteger();
        Item loaded = new Item(7L);
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> {
                    loadCount.incrementAndGet();
                    return loaded;
                },
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        assertSame(loaded, repository.cacheLoad(7L));
        assertSame(loaded, repository.cacheLoad(7L));
        assertEquals(1, loadCount.get());
    }

    @Test
    public void cacheLoadSkipsLoaderForCachedNewRoleDecision() {
        AtomicInteger detectorCalls = new AtomicInteger();
        AtomicInteger loadCount = new AtomicInteger();
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> {
                    loadCount.incrementAndGet();
                    return new Item(id);
                },
                item -> item.id,
                List::of,
                CacheConfig.defaults(),
                NewRolePolicy.of(roleId -> {
                    detectorCalls.incrementAndGet();
                    return true;
                }, Duration.ofMinutes(5)));

        assertNull(repository.cacheLoad(7L));
        assertNull(repository.cacheLoad(7L));
        assertEquals(1, detectorCalls.get());
        assertEquals(0, loadCount.get());
    }

    @Test
    public void cacheLoadUsesLoaderWhenRoleIsNotNew() {
        AtomicInteger loadCount = new AtomicInteger();
        Item loaded = new Item(7L);
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> {
                    loadCount.incrementAndGet();
                    return loaded;
                },
                item -> item.id,
                List::of,
                CacheConfig.defaults(),
                NewRolePolicy.of(roleId -> false, Duration.ofMinutes(5)));

        assertSame(loaded, repository.cacheLoad(7L));
        assertEquals(1, loadCount.get());
    }

    @Test
    public void cacheDeletePreventsReloadBeforeAsyncDeleteIsFlushed() {
        RecordingSubmitter submitter = new RecordingSubmitter();
        AtomicInteger loadCount = new AtomicInteger();
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                submitter,
                "item",
                id -> {
                    loadCount.incrementAndGet();
                    return new Item(id);
                },
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        assertNotNull(repository.cacheLoad(1L));

        Item loaded = repository.cacheLoad(1L);
        repository.cacheDelete(1L);

        assertNull(repository.cacheLoad(1L));
        assertEquals(1, loadCount.get());
        assertEquals(WriteTaskSubmitter.Op.DELETE, submitter.op);
        assertSame(loaded, submitter.entity);
        assertEquals(1L, submitter.id);
    }

    @Test
    public void cacheInsertClearsLocalDeleteMarker() {
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> new Item(id),
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        repository.cacheDelete(1L);
        Item inserted = new Item(1L);
        repository.cacheInsert(inserted);

        assertSame(inserted, repository.cacheLoad(1L));
    }

    @Test
    public void warmUpCanReplaceDeleteMarker() {
        AtomicInteger loadCount = new AtomicInteger();
        Item warmed = new Item(1L);
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> {
                    loadCount.incrementAndGet();
                    return warmed;
                },
                item -> item.id,
                () -> List.of(new Item(1L)),
                CacheConfig.defaults());

        repository.cacheDelete(1L);
        repository.warmUp(List.of(1L));

        assertSame(warmed, repository.cacheLoad(1L));
        assertEquals(1, loadCount.get());
    }

    @Test
    public void reportsWhetherWarmUpAllIsAvailable() {
        UniqueCacheRepository<Item, Long> withoutAllLoader = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> new Item(id),
                item -> item.id,
                null,
                CacheConfig.defaults());
        UniqueCacheRepository<Item, Long> withAllLoader = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> new Item(id),
                item -> item.id,
                List::of,
                CacheConfig.defaults());

        assertFalse(withoutAllLoader.supportsWarmUpAll());
        assertTrue(withAllLoader.supportsWarmUpAll());
    }

    @Test
    public void warmUpAllWithoutLoaderFailsWithActionableMessage() {
        UniqueCacheRepository<Item, Long> repository = new UniqueCacheRepository<>(
                (entityName, op, entity, id) -> {
                },
                "item",
                id -> new Item(id),
                item -> item.id,
                null,
                CacheConfig.defaults());

        try {
            repository.warmUpAll();
            fail("Expected warmUpAll configuration failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("allLoader"));
            assertTrue(expected.getMessage().contains("item"));
        }
    }

    private static class RecordingSubmitter implements WriteTaskSubmitter {
        private Op op;
        private Object entity;
        private Object id;
        @Override
        public void submit(String entityName, Op op, Object entity, Object id) {
            this.op = op;
            this.entity = entity;
            this.id = id;
        }
    }

    private record Item(long id) {
    }
}
