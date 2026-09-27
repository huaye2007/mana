package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mysql.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogContractTest {
    @Table(name="actions")
    static class Log {
        @Column long roleId;
        @Column @PartitionKey(type=PartitionType.MONTH) long time;
        @Column String action;
        Log(long role,long time,String action) { this.roleId=role; this.time=time; this.action=action; }
    }
    static final class Logs extends LogRepository<Log> {}
    @Table(name="value_logs")
    static class ValueLog { @Column @PartitionKey String partition; }
    static final class ValueLogs extends LogRepository<ValueLog> {}
    static class Access implements MysqlAccess {
        List<String> sqls = new ArrayList<>(); List<List<Object[]>> batches = new ArrayList<>(); boolean fail;
        public <T> T queryOne(String s,Object[] a,RowMapper<T> m) { throw new AssertionError("Log must not query schema"); }
        public <T> List<T> query(String s,Object[] a,RowMapper<T> m) { throw new AssertionError("Log must not query schema"); }
        public int update(String s,Object[] a) { throw new AssertionError("Log must not execute DDL"); }
        public int[] batchUpdate(String s,List<Object[]> a) {
            if (fail) throw new IllegalStateException("log storage");
            sqls.add(s); batches.add(a); return new int[a.size()];
        }
        public <T> T transaction(TransactionAction<T> a) { throw new AssertionError("Log must not transact"); }
    }
    @Test void closeDrainsPartitionsAndBatchesWithoutSchemaInitialization() {
        var access = new Access();
        var data=GameDataBuilder.builder().logRepositories(access,Logs.class).batchSize(2).build();
        var logs=data.repository(Logs.class);
        long september=Instant.parse("2026-09-30T23:59:59Z").toEpochMilli();
        logs.insert(new Log(1,september,"a")); logs.insert(new Log(2,september,"b"));
        logs.insert(new Log(3,september+1000,"c"));
        data.close();
        assertEquals(2,access.batches.size());
        assertTrue(access.sqls.getFirst().contains("actions_202609"));
        assertTrue(access.sqls.getLast().contains("actions_202610"));
        assertThrows(DataOperationException.class,()->logs.insert(new Log(4,september,"d")));
    }
    @Test void logFailuresReachSameHandlerAndDoNotBlockLaterPartitions() {
        var access=new Access(); access.fail=true; var failures=new ArrayList<DataFailure>();
        var data=GameDataBuilder.builder().logRepositories(access,Logs.class).errorHandler(f->{
            failures.add(f); access.fail=false;
        }).build();
        var logs=data.repository(Logs.class);
        logs.insert(new Log(1,Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),"a"));
        logs.insert(new Log(2,Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(),"b"));
        assertThrows(DataSaveException.class,data::close);
        assertEquals(DataOperation.LOG_INSERT,failures.getFirst().operation());
        assertEquals(1,access.batches.size());
    }
    @Test void partitionTimezoneAndInvalidSuffix() {
        var access=new Access();
        try(var data=GameDataBuilder.builder().logRepositories(access,Logs.class,ValueLogs.class)
                .partitionZone(ZoneId.of("Asia/Shanghai")).build()) {
            data.repository(Logs.class).insert(new Log(1,Instant.parse("2026-09-30T20:00:00Z").toEpochMilli(),"a"));
            var bad=new ValueLog(); bad.partition="x;DROP TABLE";
            assertThrows(IllegalArgumentException.class,()->data.repository(ValueLogs.class).insert(bad));
        }
        assertTrue(access.sqls.getFirst().contains("actions_202610"));
    }
}
