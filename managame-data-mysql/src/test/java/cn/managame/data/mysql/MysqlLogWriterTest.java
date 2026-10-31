package cn.managame.data.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.DataException;
import cn.managame.data.GameData;
import cn.managame.data.mysql.Fixtures.Database;
import cn.managame.data.mysql.Fixtures.ItemLog;
import cn.managame.data.mysql.Fixtures.ItemLogs;
import cn.managame.data.mysql.Fixtures.ReportedFailures;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class MysqlLogWriterTest {
    private static final long OCT_30 = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli();
    private static final long OCT_31 = Instant.parse("2026-10-31T15:00:00Z").toEpochMilli();
    private final Database database = Fixtures.database();
    private final ReportedFailures reported = new ReportedFailures();

    private GameData start(MysqlLogWriter writer) {
        return GameData.builder().logRepositories(writer, List.of(ItemLogs.class)).errorHandler(reported)
                .flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).build();
    }

    @Test
    void insertsLogsIntoTheirDaysTablesInOneBatch() {
        try (GameData data = start(new MysqlLogWriter(database))) {
            ItemLogs logs = data.repository(ItemLogs.class);
            logs.insert(new ItemLog(100, 5001, 1, OCT_30));
            logs.insert(new ItemLog(100, 5001, -1, OCT_31));
            logs.insert(new ItemLog(200, 5002, 3, OCT_31));
            data.flush();
        }
        assertEquals(List.of(List.of(100L, 5001, 1, OCT_30)), database.rows("SELECT * FROM item_log_20261030"));
        assertEquals(2, database.rows("SELECT * FROM item_log_20261031").size());
        assertEquals(List.of(), database.rows("SELECT * FROM item_log"));
    }

    @Test
    void cutsDaysInThePartitionZone() {
        long lateOnOct30InUtc = Instant.parse("2026-10-30T20:00:00Z").toEpochMilli();   // Oct 31 in Shanghai
        try (GameData data = start(new MysqlLogWriter(database, null, ZoneId.of("Asia/Shanghai")))) {
            data.repository(ItemLogs.class).insert(new ItemLog(100, 5001, 1, lateOnOct30InUtc));
            data.flush();
        }
        assertEquals(1, database.rows("SELECT * FROM item_log_20261031").size());
    }

    @Test
    void dropsALogWithoutATableAndWritesTheOthers() {
        long noTableYet = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();   // H2 cannot create it LIKE the base table
        try (GameData data = start(new MysqlLogWriter(database))) {
            ItemLogs logs = data.repository(ItemLogs.class);
            logs.insert(new ItemLog(100, 5001, 1, OCT_30));
            logs.insert(new ItemLog(100, 5001, 2, noTableYet));
            assertThrows(DataException.class, data::flush);
        }
        assertEquals(1, database.rows("SELECT * FROM item_log_20261030").size());
        assertEquals(1, reported.all().size());
    }
}
