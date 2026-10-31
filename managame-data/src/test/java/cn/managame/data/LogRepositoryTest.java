package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOG_SAVE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.Fixtures.ItemLog;
import cn.managame.data.Fixtures.ItemLogs;
import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import cn.managame.data.spi.LogWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Logs go wherever the application's LogWriter sends them, such as a log service over HTTP. */
class LogRepositoryTest {
    private final ReportedFailures reported = new ReportedFailures();

    /** A log service the application reports to; it answers 503 while busy. */
    static final class LogService implements LogWriter {
        final List<Object> received = new ArrayList<>();
        volatile boolean busy;

        /** What the application's HTTP client throws for a 503. */
        static final class Unavailable extends Exception {}

        @Override public synchronized void write(Class<?> logType, List<Object> logs) throws Unavailable {
            if (busy) throw new Unavailable();
            received.addAll(logs);
        }

        @Override public boolean isRetryable(Throwable failure) { return failure instanceof Unavailable || LogWriter.super.isRetryable(failure); }
    }

    private GameData start(LogWriter writer, int capacity) {
        return GameData.builder().logRepositories(writer, List.of(ItemLogs.class)).errorHandler(reported)
                .logQueueCapacity(capacity).flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).build();
    }

    @Test
    void writesLogsInBatchesInOrder() {
        LogService service = new LogService();
        try (GameData data = start(service, 100)) {
            ItemLogs logs = data.repository(ItemLogs.class);
            for (int i = 0; i < 3; i++) assertTrue(logs.insert(new ItemLog(100, 5001, i)));
            data.flushLogs();
        }
        assertEquals(List.of(new ItemLog(100, 5001, 0), new ItemLog(100, 5001, 1), new ItemLog(100, 5001, 2)), service.received);
    }

    @Test
    void keepsLogsTheWriterCallsRetryable() {
        LogService service = new LogService();
        try (GameData data = start(service, 100)) {
            data.repository(ItemLogs.class).insert(new ItemLog(100, 5001, 1));
            service.busy = true;
            assertThrows(DataException.class, data::flushLogs);
            service.busy = false;
            data.flushLogs();
        }
        assertEquals(List.of(new ItemLog(100, 5001, 1)), service.received);
        assertEquals(List.of(), reported.all());
    }

    @Test
    void dropsALogTheWriterRefusesAndWritesTheOthers() {
        List<Object> written = new ArrayList<>();
        LogWriter refusesNegative = (logType, logs) -> {
            for (Object log : logs) if (((ItemLog) log).delta() < 0) throw new IllegalArgumentException("negative delta");
            written.addAll(logs);
        };
        try (GameData data = start(refusesNegative, 100)) {
            ItemLogs logs = data.repository(ItemLogs.class);
            logs.insert(new ItemLog(100, 5001, 1));
            logs.insert(new ItemLog(100, 5001, -1));
            logs.insert(new ItemLog(100, 5001, 2));
            assertThrows(DataException.class, data::flushLogs);
        }
        assertEquals(List.of(new ItemLog(100, 5001, 1), new ItemLog(100, 5001, 2)), written);
        assertEquals(1, reported.all().size());
        assertEquals(new ItemLog(100, 5001, -1), reported.all().getFirst().value());
    }

    @Test
    void refusesLogsBeyondTheQueuesCapacity() {
        LogService service = new LogService();
        try (GameData data = start(service, 2)) {
            ItemLogs logs = data.repository(ItemLogs.class);
            assertTrue(logs.insert(new ItemLog(100, 5001, 1)));
            assertTrue(logs.insert(new ItemLog(100, 5001, 2)));
            ItemLog third = new ItemLog(100, 5001, 3);
            assertFalse(logs.insert(third));
            assertEquals(DATA_LOG_SAVE_FAILED, reported.all().getFirst().errorCode());
            assertEquals(third, reported.all().getFirst().value());
        }
        assertEquals(2, service.received.size());
    }

    @Test
    void aLogDestinationThatHangsOrFailsNeverHoldsUpEntities() throws InterruptedException {
        MemoryMapper database = new MemoryMapper();
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        LogWriter hanging = (logType, logs) -> {
            writing.countDown();
            release.await();
            throw new IllegalArgumentException("refused");
        };
        try (GameData data = GameData.builder().repositories(database, Players.class).logRepositories(hanging, ItemLogs.class)
                .errorHandler(reported).flushInterval(Duration.ofMillis(20)).build()) {
            data.repository(ItemLogs.class).insert(new ItemLog(100, 5001, 1));
            assertTrue(writing.await(5, TimeUnit.SECONDS), "a log round waits in the writer");
            data.repository(Players.class).insert(new Player(1, "ann"));
            data.flush();
            assertEquals(1, database.rows(Player.class).size(), "written while the logs' round waits");
            release.countDown();
        }
        assertEquals(DATA_LOG_SAVE_FAILED, reported.all().getFirst().errorCode());
    }

    @Test
    void refusesLogsAfterClose() {
        GameData data = start(new LogService(), 10);
        data.close();
        assertThrows(IllegalStateException.class, () -> data.repository(ItemLogs.class).insert(new ItemLog(1, 1, 1)));
    }
}
