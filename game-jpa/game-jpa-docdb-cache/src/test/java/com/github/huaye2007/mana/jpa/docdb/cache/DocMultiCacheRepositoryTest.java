package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheCompositeKey;
import com.github.huaye2007.mana.jpa.cache.annotation.CacheKey;
import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.starter.GameJpaBootstrap;
import com.github.huaye2007.mana.jpa.starter.GameJpaContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

public class DocMultiCacheRepositoryTest {

    @Test
    public void cacheLoadGroupsByCompositeKeyAndCachesBucket() {
        RecordingDocExecutor executor = new RecordingDocExecutor(List.of(
                new Mail(1L, 100L, "a"),
                new Mail(2L, 100L, "b"),
                new Mail(3L, 200L, "c")));
        GameJpaContext context = bootstrap(executor);

        try {
            MailRepository repository = context.getRepository(MailRepository.class);

            List<Mail> loaded = repository.cacheLoad(CacheCompositeKey.of(100L));
            assertEquals(2, loaded.size());
            assertEquals(1, executor.findCalls);

            // 第二次读同一组合键必须命中缓存,不再回库
            repository.cacheLoad(CacheCompositeKey.of(100L));
            assertEquals(1, executor.findCalls);

            List<Mail> other = repository.cacheLoad(CacheCompositeKey.of(200L));
            assertEquals(1, other.size());
            assertEquals(2, executor.findCalls);
        } finally {
            context.close();
        }
    }

    @Test
    public void cacheWritesUpdateBucketWithoutReload() {
        RecordingDocExecutor executor = new RecordingDocExecutor(List.of(new Mail(1L, 100L, "a")));
        GameJpaContext context = bootstrap(executor);

        try {
            MailRepository repository = context.getRepository(MailRepository.class);
            assertEquals(1, repository.cacheLoad(CacheCompositeKey.of(100L)).size());

            Mail inserted = new Mail(2L, 100L, "b");
            repository.cacheInsert(inserted);
            List<Mail> afterInsert = repository.cacheLoad(CacheCompositeKey.of(100L));
            assertEquals(2, afterInsert.size());

            repository.cacheDelete(inserted);
            assertEquals(1, repository.cacheLoad(CacheCompositeKey.of(100L)).size());
            assertEquals(1, executor.findCalls);
        } finally {
            context.close();
        }
    }

    @Test
    public void multiCacheRequiresCacheKeyFields() {
        RecordingDocExecutor executor = new RecordingDocExecutor(List.of());
        GameJpaContext context = bootstrap(executor, List.of(NoCacheKeyDoc.class));

        try {
            assertThrows(IllegalStateException.class,
                    () -> context.getRepository(NoCacheKeyRepository.class));
        } finally {
            context.close();
        }
    }

    private GameJpaContext bootstrap(RecordingDocExecutor executor) {
        return bootstrap(executor, List.of(Mail.class));
    }

    private GameJpaContext bootstrap(RecordingDocExecutor executor, List<Class<?>> entities) {
        return new GameJpaBootstrap()
                .install(DocdbCacheModule.withExecutor(executor))
                .bootstrap(entities);
    }

    interface MailRepository extends IDocMultiCacheRepository<Mail, Long> {
    }

    interface NoCacheKeyRepository extends IDocMultiCacheRepository<NoCacheKeyDoc, Long> {
    }

    @Document(collection = "mail")
    static class Mail {
        @Id
        private long id;
        @CacheKey(order = 0)
        private long roleId;
        private String title;

        Mail() {
        }

        Mail(long id, long roleId, String title) {
            this.id = id;
            this.roleId = roleId;
            this.title = title;
        }
    }

    @Document(collection = "no_cache_key")
    static class NoCacheKeyDoc {
        @Id
        private long id;
    }
}
