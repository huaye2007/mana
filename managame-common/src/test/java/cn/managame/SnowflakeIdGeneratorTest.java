package cn.managame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final int SERVER = 101, ITEM = 3;

    @Test void idsHoldTheirTimeDataAndSequenceAndIncrease() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(IdLayout.DEFAULT, SERVER);
        Instant before = Instant.now().minusMillis(1);
        long first = ids.generateId(), second = ids.generateId();
        assertTrue(second > first && first > 0);
        IdLayout layout = ids.layout();
        assertEquals(SERVER, layout.data(first));
        assertFalse(layout.time(first).isBefore(before) || layout.time(second).isAfter(Instant.now()));
        assertTrue(layout.sequence(second) == layout.sequence(first) + 1 || layout.time(second).isAfter(layout.time(first)));
    }

    @Test void dataLaidOutPerIdIsReadBackAndSharesTheSequence() {
        // 10 bits of data: 6 for the server, 4 for a kind of entity
        int server = 21;
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(IdLayout.DEFAULT, server << 4);
        long item = ids.generateId(server << 4 | ITEM), mail = ids.generateId();
        long data = ids.layout().data(item);
        assertEquals(server, data >>> 4);
        assertEquals(ITEM, data & 0xF);
        assertTrue(mail > item, "one sequence for all data");
        assertThrows(IllegalArgumentException.class, () -> ids.generateId(1 << 10), "too big for 10 bits");
        assertThrows(IllegalArgumentException.class, () -> ids.generateId(-1));
    }

    @Test void manyThreadsGetUniqueIdsEachInIncreasingOrder() throws Exception {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(IdLayout.DEFAULT, SERVER);
        int threads = 8, each = 100_000;
        List<Future<long[]>> made = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++)
                made.add(pool.submit(() -> {
                    long[] mine = new long[each];
                    for (int i = 0; i < each; i++) mine[i] = ids.generateId();
                    return mine;
                }));
            Set<Long> all = new HashSet<>();
            for (Future<long[]> future : made) {
                long[] mine = future.get();
                for (int i = 0; i < each; i++) {
                    assertTrue(all.add(mine[i]), "unique");
                    if (i > 0) assertTrue(mine[i] > mine[i - 1], "increasing");
                }
            }
            assertEquals(threads * each, all.size());
        }
    }

    @Test void aUsedUpSequenceWaitsForTheNextTickAndNoIdIsAheadOfTheClock() {
        // four ids a 20 ms tick: forty ids take nine more ticks
        IdLayout layout = new IdLayout(EPOCH, Duration.ofMillis(20), 40, 2, 0);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(layout, 0);
        long start = System.nanoTime(), previous = 0;
        for (int i = 0; i < 40; i++) {
            long id = ids.generateId();
            assertTrue(id > previous);
            assertFalse(layout.time(id).isAfter(Instant.now()), "never ahead of the clock");
            previous = id;
        }
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(9 * 20 - 20).toNanos(), "waited for the ticks");
    }

    @Test void aLayoutForJavaScriptKeepsIdsUnderTwoToTheFiftyThird() {
        IdLayout small = new IdLayout(EPOCH, Duration.ofSeconds(1), 31, 10, 12);
        long id = new SnowflakeIdGenerator(small, small.maxData()).generateId();
        assertTrue(id < 1L << 53);
        assertEquals(4095, small.data(id));
        assertTrue(small.end().isAfter(Instant.parse("2094-01-01T00:00:00Z")));
    }

    @Test void refusesLayoutsAndDataItCannotUse() {
        assertThrows(IllegalArgumentException.class, () -> new IdLayout(EPOCH, Duration.ofMillis(1), 41, 12, 11), "64 bits");
        assertThrows(IllegalArgumentException.class, () -> new IdLayout(EPOCH, Duration.ofNanos(500), 41, 12, 10));
        assertThrows(IllegalArgumentException.class, () -> new IdLayout(EPOCH, Duration.ofMillis(1), 0, 12, 10));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(IdLayout.DEFAULT, 1024));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(
                new IdLayout(Instant.now().plusSeconds(60), Duration.ofMillis(1), 41, 12, 10), 0), "epoch in the future");
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(
                new IdLayout(Instant.parse("2000-01-01T00:00:00Z"), Duration.ofMillis(1), 30, 12, 10), 0), "time ran out");
    }
}
