package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.error.DataOperation;
import cn.managame.data.error.DataOperationException;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

/** One save pass covers a finite boundary, so producers that outrun storage cannot stop it from completing. */
class SavePassBoundaryTest {
    static class Player { @Id long id; int score; Player() {} Player(long id) { this.id = id; } }
    static final class Players extends SingleRepository<Long,Player> {}

    /** Records every saved id and lets a test inject work while a batch is being saved. */
    static final class SavingMapper implements EntityMapper {
        final List<Object> saved = new CopyOnWriteArrayList<>();
        volatile Runnable duringSave = () -> {};
        volatile long perBatchNanos;
        public void initialize(EntityMeta meta) {}
        public Object load(EntityMeta meta, Object id) { return null; }
        public List<?> loadGroup(EntityMeta meta, GroupKeys key) { return List.of(); }
        public void insertBatch(EntityMeta m, List<?> e) { save(e); }
        public void updateBatch(EntityMeta m, List<?> e) { save(e); }
        public void deleteBatch(EntityMeta m, List<Object> e) { save(e); }
        public void deleteInsertBatch(EntityMeta m, List<?> e) { save(e); }
        private void save(List<?> batch) {
            duringSave.run();
            if (perBatchNanos > 0) LockSupport.parkNanos(perBatchNanos);
            saved.addAll(batch);
        }
    }

    /** A pass claims the dirty set it found and ends, instead of following a producer that never stops. */
    @Test @Timeout(60) void aPassStopsAtTheDirtySetItStartedWithWhileAProducerKeepsWriting() throws Exception {
        var meta = new EntityMeta(Player.class, EntityMeta.Kind.SINGLE);
        var buffer = new PendingBuffer(Set.of(meta));
        var running = new AtomicBoolean(true);
        var produced = new AtomicLong();
        var producer = Thread.ofPlatform().daemon().start(() -> {
            while (running.get()) {
                long id = produced.incrementAndGet();
                buffer.record(meta, id, DataOperation.INSERT, new Player(id));
            }
        });
        try {
            while (buffer.size() < 10_000) Thread.onSpinWait();     // a backlog the pass has to work through
            List<PendingBuffer.Claimed> claimed = buffer.claim(meta);
            long producedByThen = produced.get();
            assertFalse(claimed.isEmpty());
            assertTrue(claimed.size() < producedByThen,
                    "the pass followed the producer instead of stopping: claimed " + claimed.size() + " of " + producedByThen);
        } finally { running.set(false); producer.join(); }
        assertTrue(buffer.size() > 0, "what the pass did not claim stays pending for the next one");
    }

    /**
     * Changes recorded while a pass runs belong to the next pass, so passes keep ending and
     * {@link DataStats#flushes()} keeps advancing instead of one pass absorbing a continuous stream.
     */
    @Test @Timeout(60) void passesKeepAdvancingWhileNewChangesArriveDuringEveryPass() {
        var mapper = new SavingMapper();
        var accepted = new ArrayList<Long>();
        try (var data = GameDataBuilder.builder().repositories(mapper, Players.class)
                .flushInterval(Duration.ofMinutes(1)).build()) {
            var players = data.repository(Players.class);
            var next = new AtomicLong();
            var rounds = new AtomicInteger(4);
            for (int i = 0; i < 3; i++) accepted.add(next.incrementAndGet());
            accepted.forEach(id -> players.insert(new Player(id)));
            mapper.duringSave = () -> {
                if (rounds.getAndDecrement() <= 0) return;
                for (int i = 0; i < 3; i++) {
                    long id = next.incrementAndGet();
                    synchronized (accepted) { accepted.add(id); }
                    players.insert(new Player(id));
                }
            };
            for (int pass = 1; pass <= 5; pass++) {
                data.writer.flush();
                assertEquals(pass, data.stats().flushes(), "every pass must end");
                assertEquals(3L * pass, mapper.saved.size(), "a pass saves only what it claimed");
                assertEquals(pass < 5 ? 3 : 0, data.stats().pendingChanges(), "work recorded during a pass waits");
            }
        }
        assertEquals(accepted.size(), mapper.saved.size());
        assertEquals(new HashSet<>(accepted), savedIds(mapper));
    }

    /**
     * Close drains every change it accepted, including a backlog that outlived the passes before it: bounded
     * passes must keep repeating until nothing accepted is left, not stop at the first pass that keeps nothing.
     */
    @Test @Timeout(120) void closeDrainsTheWholeBacklogItAccepted() throws Exception {
        var mapper = new SavingMapper();
        mapper.perBatchNanos = TimeUnit.MILLISECONDS.toNanos(1);     // storage slower than the producer
        var accepted = Collections.synchronizedList(new ArrayList<Long>());
        var data = GameDataBuilder.builder().repositories(mapper, Players.class)
                .flushInterval(Duration.ofMillis(10)).batchSize(100).build();
        var players = data.repository(Players.class);
        var producer = Thread.ofPlatform().daemon().start(() -> {
            for (long id = 1; id <= 50_000; id++) {
                try { players.insert(new Player(id)); } catch (DataOperationException closing) { return; }
                accepted.add(id);
            }
        });
        while (data.stats().pendingChanges() < 10_000) Thread.onSpinWait();   // close with a real backlog waiting
        data.close();
        producer.join();
        assertFalse(data.stats().accepting());
        assertEquals(0, data.stats().pendingChanges());
        assertEquals(new HashSet<>(accepted), savedIds(mapper), "close left accepted changes unsaved");
        assertEquals(accepted.size(), mapper.saved.size(), "a change was saved twice");
    }

    private static Set<Long> savedIds(SavingMapper mapper) {
        Set<Long> ids = new HashSet<>();
        for (Object saved : mapper.saved) ids.add(((Player) saved).id);
        return ids;
    }
}
