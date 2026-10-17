package cn.managame.data;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EntityCacheTest {
    @Test void idleCleanEntriesAreEvictedButDirtyOnesStay() throws Exception {
        var cache = new EntityCache<Long, String>(TimeUnit.MILLISECONDS.toNanos(1));
        cache.get(1L, k -> "clean"); cache.get(2L, k -> "dirty");
        Thread.sleep(5);
        cache.sweep(() -> k -> k == 2L);
        assertEquals(1, cache.size());
        assertEquals("dirty", cache.get(2L, k -> fail("must stay cached")));
    }

    @Test void aChangeMissedByTheDirtyViewKeepsItsEntry() throws Exception {
        var cache = new EntityCache<Long, String>(TimeUnit.MILLISECONDS.toNanos(1));
        String group = "group";
        cache.get(1L, k -> "single"); cache.get(2L, k -> group); cache.get(3L, k -> "idle");
        Thread.sleep(5);
        // Both changes land while the dirty view is being built and are absent from it, like a group
        // mutation racing the snapshot of pending changes: a new value, and an in-place change (same value).
        cache.sweep(() -> {
            cache.update(1L, (cached, current) -> "changed");
            cache.update(2L, (cached, current) -> current);
            sleep(5);                                            // a slow view build must not matter
            return k -> false;
        });
        assertEquals(2, cache.size());
        assertEquals("changed", cache.get(1L, k -> fail("must stay cached")));
        assertSame(group, cache.get(2L, k -> fail("must stay cached")));
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { throw new AssertionError(e); }
    }

    @Test void throttledAccessTimeNeverShortensTheIdlePeriod() throws Exception {
        long idle = TimeUnit.MILLISECONDS.toNanos(160);          // access time refreshed at most every 10 ms
        var cache = new EntityCache<Long, String>(idle);
        cache.get(1L, k -> "value");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        while (System.nanoTime() < deadline) {                   // keep reading well inside the idle period
            cache.get(1L, k -> fail("must stay cached"));
            cache.sweep(() -> k -> false);
            Thread.sleep(2);
        }
        assertEquals(1, cache.size());
    }
}
