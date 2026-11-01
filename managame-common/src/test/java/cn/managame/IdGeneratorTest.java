package cn.managame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {
    @Test void aSequenceCountsUpFromItsFirstIdAcrossThreads() throws Exception {
        IdGenerator rooms = new SequenceIdGenerator(1000);
        assertEquals(1000, rooms.generateId());
        assertEquals(1001, rooms.generateId());
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int t = 0; t < 4; t++) pool.execute(() -> { for (int i = 0; i < 10_000; i++) seen.add(rooms.generateId()); });
        }
        assertEquals(40_000, seen.size(), "unique");
        assertEquals(41_002, rooms.generateId(), "none skipped");
        assertEquals(1, new SequenceIdGenerator().generateId());
    }

    @Test void anApplicationGivesItsOwnGenerator() {
        AtomicLong reserved = new AtomicLong(500);   // as if a block reserved from a database
        IdGenerator ids = reserved::incrementAndGet;
        assertTrue(ids.generateId() < ids.generateId());
    }
}
