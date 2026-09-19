package cn.managame.support;

import cn.managame.core.access.DataAccess;
import cn.managame.core.access.Query;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.repository.SingleRepository;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.WriteBehindEngine;
import cn.managame.core.write.WriteFailureHandler;
import cn.managame.core.write.WriteOperation;

import cn.managame.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

public final class DataTestSupport {
    @Table("test_rows")
    public static class Row {
        @Id public long id;
        @GroupKey public long owner = 1;
        @MapKey public long slot;
        public int value;
        public Row() { }
        public Row(long id) { this.id = id; this.slot = id; }
    }
    @Table("events") @Document("event_docs")
    public static class Event extends Row {
        @LogPartition(LogPartition.Period.MONTH) public java.time.LocalDate createTime;
        public Event() { }
        public Event(long id, java.time.LocalDate time) { super(id); this.createTime = time; }
    }
    public interface Events extends cn.managame.core.log.LogRepository<Event> {
        default void record(Event event) { append(event); }
    }
    public interface Logs extends cn.managame.core.log.LogRepository<Row> { }
    public interface Rows extends SingleRepository<Row, Long> { }
    public static final EntityMetadata<Row> METADATA = EntityMetadata.inspect(Row.class);
    public static class Access implements DataAccess {
        public final List<Long> saved = new CopyOnWriteArrayList<>();
        public final AtomicInteger schemaCalls = new AtomicInteger();
        public volatile boolean closed;
        public <T, ID> Optional<T> findById(EntityMetadata<T> m, ID id) { return Optional.empty(); }
        public <T> List<T> find(EntityMetadata<T> m, Query query) { return List.of(); }
        public <T> void scan(EntityMetadata<T> m, Consumer<T> consumer) { }
        public void ensureSchema(EntityMetadata<?> m) { schemaCalls.incrementAndGet(); }
        public void ensureSchema(EntityMetadata<?> m, String physical) { schemaCalls.incrementAndGet(); }
        public BatchResult applyBatch(List<WriteOperation<?>> operations) {
            assertFalse(closed, "database closed before writer drain");
            for (var op : operations) if (op.id() instanceof Long id) saved.add(id);
            return BatchResult.success(operations.size());
        }
        public void close() { closed = true; }
    }
    public static WriteOperation<Row> insert(long id) { return WriteOperation.insert(METADATA, new Row(id), id, 1L); }
    public static WriteBehindEngine engine(DataAccess access, int capacity, int batch, Duration interval, WriteFailureHandler handler) {
        return new WriteBehindEngine(access, capacity, batch, interval, 0, handler);
    }
    public static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    public static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition timeout");
            try { Thread.sleep(1); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }
    public static Task task(Runnable action) { return new Task(action); }
    public static final class Task {
        public final AtomicReference<Throwable> error = new AtomicReference<>();
        public final Thread thread;
        Task(Runnable action) {
            thread = Thread.ofPlatform().daemon().start(() -> {
                try { action.run(); } catch (Throwable e) { error.set(e); }
            });
        }
        public void join() {
            try { thread.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(thread.isAlive(), "task did not finish");
            if (error.get() != null) throw new AssertionError(error.get());
        }
    }
}
