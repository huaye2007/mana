package cn.managame.rdb;

import cn.managame.core.write.*;
import cn.managame.rdb.dialect.MySqlDialect;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class RdbBatchSafetyTest {
    static final class Jdbc {
        final List<String> calls = new ArrayList<>();
        final List<Object> parameters = new ArrayList<>();
        int[] counts = {1};
        SQLException executeFailure;
        boolean rollbackFailure, commitFailure, abortFailure, exists;
        int queries;
        RdbDataAccess access() {
            return new RdbDataAccess(proxy(DataSource.class, (p, method, args) -> {
                if (!method.getName().equals("getConnection")) throw new AssertionError(method);
                return proxy(Connection.class, (c, call, values) -> switch (call.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> { calls.add("auto=" + values[0]); yield null; }
                    case "close" -> { calls.add("close"); yield null; }
                    case "commit" -> { calls.add("commit"); if (commitFailure) throw new SQLException("commit reply lost"); yield null; }
                    case "rollback" -> { calls.add("rollback"); if (rollbackFailure) throw new SQLException("rollback failed"); yield null; }
                    case "abort" -> { calls.add("abort"); if (abortFailure) throw new SQLException("abort failed"); yield null; }
                    case "prepareStatement" -> statement();
                    default -> throw new AssertionError(call);
                });
            }), new MySqlDialect());
        }
        PreparedStatement statement() {
            return proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
                case "setObject" -> { parameters.add(args[1]); yield null; }
                case "addBatch", "close" -> null;
                case "executeBatch" -> { if (executeFailure != null) throw executeFailure; yield counts; }
                case "executeQuery" -> {
                    queries++;
                    boolean[] read = {false};
                    yield proxy(ResultSet.class, (rs, call, values) -> switch (call.getName()) {
                        case "next" -> { boolean next = exists && !read[0]; read[0] = true; yield next; }
                        case "close" -> null;
                        default -> throw new AssertionError(call);
                    });
                }
                default -> throw new AssertionError(method);
            });
        }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static List<WriteOperation<?>> update() {
        return List.of(WriteOperation.update(METADATA, new Row(1), 1L, null));
    }

    public static class GroupRow {
        @cn.managame.annotation.Id public long id;
        @cn.managame.annotation.GroupKey(order = 0) public long player;
        @cn.managame.annotation.GroupKey(order = 1) public int activity;
    }
    @Test void compositeStringGroupDeleteBindsOriginalNumericTypes() {
        var jdbc = new Jdbc();
        var metadata = cn.managame.core.metadata.EntityMetadata.inspect(GroupRow.class);
        assertTrue(jdbc.access().applyBatch(List.of(WriteOperation.deleteGroup(metadata, "10001:7"))).allSuccess());
        assertEquals(List.of(10001L, 7), jdbc.parameters);
    }

    @Test void failedRollbackAbortsWithoutResettingAutoCommit() {
        var jdbc = new Jdbc(); jdbc.executeFailure = new SQLException("write failed"); jdbc.rollbackFailure = true;
        var access = jdbc.access();
        var error = assertThrows(BatchWriteException.class, () -> access.applyBatch(List.of(insert(1))));
        assertEquals(BatchItemState.UNKNOWN, error.result().stateAt(0));
        assertEquals(WriteFailureAction.FAIL, access.classifyWriteFailure(error));
        assertEquals(List.of("auto=false", "rollback", "abort", "close"), jdbc.calls);
    }
    @Test void abortFailureDoesNotReturnUnresolvedConnectionToPool() {
        var jdbc = new Jdbc(); jdbc.executeFailure = new SQLException("write failed"); jdbc.rollbackFailure = true; jdbc.abortFailure = true;
        assertThrows(BatchWriteException.class, () -> jdbc.access().applyBatch(List.of(insert(1))));
        assertEquals(List.of("auto=false", "rollback", "abort"), jdbc.calls);
    }
    @Test void uncertainCommitIsUnknownEvenIfLaterRollbackSucceeds() {
        var jdbc = new Jdbc(); jdbc.commitFailure = true;
        var error = assertThrows(BatchWriteException.class, () -> jdbc.access().applyBatch(List.of(insert(1))));
        assertEquals(BatchItemState.UNKNOWN, error.result().stateAt(0));
        assertEquals(List.of("auto=false", "commit", "rollback", "abort", "close"), jdbc.calls);
    }
    @Test void safeRollbackReportsFailedAndOnlyTransactionConflictsCanRetry() {
        var jdbc = new Jdbc(); jdbc.executeFailure = new SQLTransactionRollbackException("deadlock", "40001");
        var access = jdbc.access();
        var error = assertThrows(BatchWriteException.class, () -> access.applyBatch(List.of(insert(1))));
        assertEquals(BatchItemState.FAILED, error.result().stateAt(0));
        assertEquals(WriteFailureAction.RETRY, access.classifyWriteFailure(error));
        assertEquals(List.of("auto=false", "rollback", "auto=true", "close"), jdbc.calls);
    }
    @Test void missingUpdateRollsBackInsteadOfReportingSuccess() {
        var jdbc = new Jdbc(); jdbc.counts = new int[]{0};
        var error = assertThrows(BatchWriteException.class, () -> jdbc.access().applyBatch(update()));
        assertEquals(BatchItemState.FAILED, error.result().stateAt(0));
        assertEquals(1, jdbc.queries); assertFalse(jdbc.calls.contains("commit"));
    }
    @Test void unchangedAndUnknownCountUpdatesCheckExistenceOnTheTransaction() {
        for (int count : new int[]{0, Statement.SUCCESS_NO_INFO}) {
            var jdbc = new Jdbc(); jdbc.counts = new int[]{count}; jdbc.exists = true;
            assertTrue(jdbc.access().applyBatch(update()).allSuccess());
            assertEquals(1, jdbc.queries); assertTrue(jdbc.calls.contains("commit"));
        }
        var jdbc = new Jdbc(); jdbc.counts = new int[]{Statement.SUCCESS_NO_INFO};
        assertThrows(BatchWriteException.class, () -> jdbc.access().applyBatch(update()));
    }
    @Test void ordinaryUpdatesAndIdempotentDeletesDoNotIssueExtraQueries() {
        var jdbc = new Jdbc();
        assertTrue(jdbc.access().applyBatch(update()).allSuccess()); assertEquals(0, jdbc.queries);
        jdbc = new Jdbc(); jdbc.counts = new int[]{0};
        assertTrue(jdbc.access().applyBatch(List.of(WriteOperation.delete(METADATA, 1L, null))).allSuccess());
        assertEquals(0, jdbc.queries);
        jdbc = new Jdbc(); jdbc.counts = new int[]{Statement.SUCCESS_NO_INFO};
        assertTrue(jdbc.access().applyBatch(List.of(insert(1))).allSuccess());
    }
    @Test void invalidBatchCountsFailTheWholeTransaction() {
        for (int[] counts : List.of(new int[]{}, new int[]{Statement.EXECUTE_FAILED}, new int[]{0}, new int[]{2})) {
            var jdbc = new Jdbc(); jdbc.counts = counts;
            assertThrows(BatchWriteException.class, () -> jdbc.access().applyBatch(List.of(insert(1))));
            assertFalse(jdbc.calls.contains("commit")); assertTrue(jdbc.calls.contains("rollback"));
        }
    }
}
