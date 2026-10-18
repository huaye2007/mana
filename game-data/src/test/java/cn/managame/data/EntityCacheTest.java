package cn.managame.data;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EntityCacheTest {
    @Test void idleCleanEntriesAreEvictedButDirtyOnesStay() throws Exception {
        var cache = new EntityCache<Long, String>(TimeUnit.MILLISECONDS.toNanos(1), Long.MAX_VALUE);
        cache.get(1L, k -> "clean"); cache.get(2L, k -> "dirty");
        Thread.sleep(5);
        cache.sweep(() -> k -> k == 2L, true);
        assertEquals(1, cache.size());
        assertEquals("dirty", cache.get(2L, k -> fail("must stay cached")));
    }

    @Test void aChangeMissedByTheDirtyViewKeepsItsEntry() throws Exception {
        var cache = new EntityCache<Long, String>(TimeUnit.MILLISECONDS.toNanos(1), Long.MAX_VALUE);
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
        }, true);
        assertEquals(2, cache.size());
        assertEquals("changed", cache.get(1L, k -> fail("must stay cached")));
        assertSame(group, cache.get(2L, k -> fail("must stay cached")));
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { throw new AssertionError(e); }
    }

    @Test void theEntryBoundEvictsTheLeastRecentlyUsedCleanEntriesOnly() throws Exception {
        long idle = TimeUnit.MILLISECONDS.toNanos(160);          // access time refreshed at most every 10 ms
        var cache = new EntityCache<Long, String>(idle, 4);
        for (long key = 1; key <= 8; key++) cache.get(key, k -> "v" + k);
        Thread.sleep(15);                                        // past the refresh throttle, inside the idle time
        cache.get(1L, k -> fail("cached")); cache.get(2L, k -> fail("cached"));   // now the most recently read
        cache.sweep(() -> k -> k == 3L, true);                   // 3 is dirty and cannot be evicted
        assertEquals(4, cache.size());
        for (long key : new long[] {1, 2, 3}) assertNotNull(cache.get(key, k -> fail("evicted " + k)));
        int kept = 0;
        for (long key = 4; key <= 8; key++) if (cache.get(key, k -> null) != null) kept++;
        assertEquals(1, kept, "entries read in the same refresh window are not ordered against each other");
    }

    @Test void theEntryBoundNeverEvictsUnsavedWorkEvenWhenThatLeavesItOver() {
        var cache = new EntityCache<Long, String>(TimeUnit.MINUTES.toNanos(30), 2);
        for (long key = 1; key <= 6; key++) cache.get(key, k -> "v" + k);
        cache.sweep(() -> k -> k >= 3, true);                    // four dirty entries, a bound of two
        assertEquals(4, cache.size());
        for (long key = 3; key <= 6; key++) assertNotNull(cache.get(key, k -> fail("dropped unsaved work")));
    }

    @Test void aSweepBetweenIdleSweepsEnforcesOnlyTheEntryBound() {
        var cache = new EntityCache<Long, String>(TimeUnit.NANOSECONDS.toNanos(1), 3);
        for (long key = 1; key <= 3; key++) cache.get(key, k -> "v" + k);
        cache.sweep(() -> fail("a cache inside its bound must not even build the dirty view"), false);
        assertEquals(3, cache.size());                           // idle, but idle eviction was not due
        cache.get(4L, k -> "v4");
        cache.sweep(() -> k -> false, false);
        assertEquals(3, cache.size());
    }

    @Test void throttledAccessTimeNeverShortensTheIdlePeriod() throws Exception {
        long idle = TimeUnit.MILLISECONDS.toNanos(160);          // access time refreshed at most every 10 ms
        var cache = new EntityCache<Long, String>(idle, Long.MAX_VALUE);
        cache.get(1L, k -> "value");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        while (System.nanoTime() < deadline) {                   // keep reading well inside the idle period
            cache.get(1L, k -> fail("must stay cached"));
            cache.sweep(() -> k -> false, true);
            Thread.sleep(2);
        }
        assertEquals(1, cache.size());
    }
}
