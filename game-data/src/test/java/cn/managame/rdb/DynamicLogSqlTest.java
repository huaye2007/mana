package cn.managame.rdb;

import cn.managame.core.write.WriteOperation;
import cn.managame.rdb.dialect.MySqlDialect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLogSqlTest {
    @Test void dynamicDestinationsReuseEntityTemplateAndBindLiveValuesInBatches() throws Exception {
        var sqls = new ArrayList<String>();
        var batches = new ArrayList<List<Object>>();
        DataSource source = proxy(DataSource.class, (p, method, args) -> {
            if (!method.getName().equals("getConnection")) throw new AssertionError(method);
            return proxy(Connection.class, (c, call, values) -> switch (call.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit", "commit", "close" -> null;
                case "prepareStatement" -> {
                    sqls.add((String) values[0]);
                    var parameters = new TreeMap<Integer, Object>();
                    int[] count = {0};
                    yield proxy(PreparedStatement.class, (ps, operation, arguments) -> switch (operation.getName()) {
                        case "setObject" -> { parameters.put((Integer) arguments[0], arguments[1]); yield null; }
                        case "addBatch" -> { batches.add(new ArrayList<>(parameters.values())); count[0]++; yield null; }
                        case "executeBatch" -> { int[] counts = new int[count[0]]; Arrays.fill(counts, 1); yield counts; }
                        case "close" -> null;
                        default -> throw new AssertionError(operation);
                    });
                }
                default -> throw new AssertionError(call);
            });
        });
        var access = new RdbDataAccess(source, new MySqlDialect());
        for (int table = 0; table < 40; table++) {
            Row first = new Row(1); Row second = new Row(2);
            var one = WriteOperation.insert(METADATA, first, 1L, null, "events_" + table);
            var two = WriteOperation.insert(METADATA, second, 2L, null, "events_" + table);
            first.value = 73; second.value = 91; // Encoding occurs when the batch executes.
            assertTrue(access.applyBatch(List.of(one, two)).allSuccess());
            assertTrue(sqls.get(table).startsWith("INSERT INTO `events_" + table + "` ("));
            assertFalse(sqls.get(table).contains("test_rows"));
            int valueColumn = 0;
            for (int i = 0; i < METADATA.properties().size(); i++) {
                if (METADATA.properties().get(i).rdbName().equals("value")) valueColumn = i;
            }
            assertEquals(73, batches.get(table * 2).get(valueColumn));
            assertEquals(91, batches.get(table * 2 + 1).get(valueColumn));
        }
        // Bound memory is part of the contract: historical physical names must not grow the plan cache.
        var field = RdbDataAccess.class.getDeclaredField("plans"); field.setAccessible(true);
        assertEquals(Set.of(Row.class), ((Map<?, ?>) field.get(access)).keySet());
        Row normal = new Row(3);
        assertTrue(access.applyBatch(List.of(WriteOperation.insert(METADATA, normal, 3L, null))).allSuccess());
        assertTrue(sqls.getLast().startsWith("INSERT INTO `test_rows` ("));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
