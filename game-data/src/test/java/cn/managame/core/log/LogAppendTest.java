package cn.managame.core.log;

import cn.managame.annotation.LogPartition;
import cn.managame.core.DataException;
import cn.managame.core.GameData;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.repository.SingleRepository;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.WriteOperation;
import java.time.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LogAppendTest {
    record Saved(String table, long id) { }
    static class RecordingAccess extends Access {
        final List<Saved> inserts = new CopyOnWriteArrayList<>();
        @Override public BatchResult applyBatch(List<WriteOperation<?>> operations) {
            String table = operations.getFirst().physicalName();
            for (var op : operations) {
                assertEquals(table, op.physicalName(), "batch must use one physical destination");
                Row row = (Row) op.entity();
                inserts.add(new Saved(table == null ? defaultPhysicalName(op.metadata()) : table, row.id));
            }
            return super.applyBatch(operations);
        }
    }
    interface AliasEvents extends LogRepository<Event> { }
    interface Mixed extends SingleRepository<Row, Long>, LogRepository<Row> { }
    @SuppressWarnings("rawtypes") interface Raw extends LogRepository { }

    @Test void businessInterfaceFactoryRoutesByEventTime() {
        var first = new RecordingAccess(); var second = new RecordingAccess();
        try (var data = GameData.builder().dataAccess("first", first).dataAccess("second", second)
                .logFlushInterval(Duration.ofMillis(5)).build()) {
            Events logs = data.repository(Events.class);
            assertSame(data.repository(Events.class), logs);
            logs.record(new Event(1, LocalDate.of(2026, 9, 30)));
            logs.append(new Event(1, LocalDate.of(2026, 10, 1)));
            data.repository(AliasEvents.class).append(new Event(2, LocalDate.of(2026, 9, 1)));
            var other = data.repository("second", Events.class);
            other.append(new Event(3, LocalDate.of(2026, 9, 1))); other.flush();
            assertEquals(List.of(new Saved("events_202609", 3)), second.inserts);
            logs.flush();
            assertEquals(3, first.inserts.size());
            assertTrue(first.inserts.contains(new Saved("events_202609", 1)));
            assertTrue(first.inserts.contains(new Saved("events_202610", 1)));
            assertEquals(0, first.schemaCalls.get() + second.schemaCalls.get());
            assertThrows(DataException.class, () -> logs.append(new Event(4, null)));
            assertThrows(DataException.class, () -> data.repository(Mixed.class));
            assertThrows(DataException.class, () -> data.repository(Raw.class));
        }
    }

    @Test void unpartitionedLogsUseDefaultTableAndCloseDrainsWithoutDdl() {
        var access = new RecordingAccess(); Logs logs;
        try (var data = new GameData(access)) {
            logs = data.repository(Logs.class);
            logs.append(new Row(1)); logs.append(new Row(1));
        }
        assertEquals(List.of(new Saved("test_rows", 1), new Saved("test_rows", 1)), access.inserts);
        assertEquals(0, access.schemaCalls.get()); assertTrue(access.closed);
        assertThrows(IllegalStateException.class, () -> logs.append(new Row(2)));
    }

    @Test void idlePartitionsRetireAndCanBeRevisitedWithoutLosingRecords() {
        var access = new RecordingAccess();
        try (var data = GameData.builder().dataAccess("log", access).logBatchSize(8)
                .logFlushInterval(Duration.ofMillis(2)).logWriterIdleTimeout(Duration.ofMillis(1)).build()) {
            var logs = data.repository(Events.class);
            for (int round = 0; round < 2; round++) {
                for (int month = 1; month <= 12; month++) {
                    logs.append(new Event(1, LocalDate.of(2026, month, 1)));
                    logs.append(new Event(1, LocalDate.of(2026, month, 1)));
                }
                logs.flush();
                until(() -> data.physicalLogWriterMetrics("log").isEmpty());
            }
            assertEquals(48, access.inserts.size());
            assertEquals(48, data.logWriterMetrics("log", Event.class).successfulOperations());
        }
    }

    static class InstantLog extends Row {
        @LogPartition(value = LogPartition.Period.DAY, zone = "Asia/Shanghai") Instant time;
    }
    static class MillisLog extends Row { @LogPartition(LogPartition.Period.YEAR) long time; }
    static class LocalLog extends Row { @LogPartition(LogPartition.Period.MONTH) LocalDateTime time; }
    @Test void partitionPeriodsAndZonesUseTheEventTimestamp() {
        var instant = new InstantLog(); instant.time = Instant.parse("2024-02-28T16:00:00Z");
        assertEquals("events_20240229", LogPartitioner.inspect(EntityMetadata.inspect(InstantLog.class), "events").table(instant));
        var millis = new MillisLog(); millis.time = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();
        assertEquals("events_2027", LogPartitioner.inspect(EntityMetadata.inspect(MillisLog.class), "events").table(millis));
        var local = new LocalLog(); local.time = LocalDateTime.of(2026, 12, 31, 23, 59);
        assertEquals("events_202612", LogPartitioner.inspect(EntityMetadata.inspect(LocalLog.class), "events").table(local));
    }

    static class Duplicate extends Event { @LogPartition(LogPartition.Period.DAY) LocalDate second; }
    static class Unsupported extends Row { @LogPartition(LogPartition.Period.DAY) String time; }
    static class Unmapped extends Row { @LogPartition(LogPartition.Period.DAY) transient LocalDate time; }
    static class BadZone extends Row { @LogPartition(value = LogPartition.Period.DAY, zone = "bad/zone") Instant time; }
    @Test void invalidPartitionDeclarationsFailAtInitialization() {
        for (Class<?> type : List.of(Duplicate.class, Unsupported.class, Unmapped.class, BadZone.class)) {
            assertThrows(DataException.class, () -> LogPartitioner.inspect(EntityMetadata.inspect(type), "events"));
        }
    }

    @Test void backendDefaultCollectionNameIsUsedForPartitions() {
        var access = new RecordingAccess() {
            @Override public String defaultPhysicalName(EntityMetadata<?> metadata) { return metadata.docCollection(); }
        };
        try (var data = new GameData(access)) {
            var logs = data.repository(Events.class);
            logs.append(new Event(1, LocalDate.of(2026, 9, 1))); logs.flush();
            assertEquals(List.of(new Saved("event_docs_202609", 1)), access.inserts);
        }
    }
}
