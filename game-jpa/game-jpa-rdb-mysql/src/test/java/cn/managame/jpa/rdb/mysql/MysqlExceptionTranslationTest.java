package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.exception.ConcurrentWriteException;
import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DataTooLargeException;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.exception.PartialBatchException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.exception.WriteTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.DataTruncation;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** JDBC 异常类型到统一重试异常的映射，不依赖 MySQL error code 或 SQLState。 */
public class MysqlExceptionTranslationTest {

    @Test
    public void mapsStandardJdbcExceptionTypesToRetryableCategories() {
        assertInstanceOf(DataTooLargeException.class, translate(
                new DataTruncation(1, true, false, 200, 100)));
        assertInstanceOf(ConcurrentWriteException.class, translate(
                new SQLTransactionRollbackException("deadlock")));
        assertInstanceOf(WriteTimeoutException.class, translate(
                new SQLTimeoutException("lock wait timeout")));
        assertInstanceOf(ConnectionException.class, translate(
                new SQLRecoverableException("connection reset")));
        assertInstanceOf(RetriableWriteException.class, translate(
                new SQLTransientException("driver-declared transient failure")));
    }

    @Test
    public void vendorCodesAndSqlStateAloneDoNotEnableRetry() {
        GameJpaException translated = translate(new SQLException("deadlock", "40001", 1213));
        assertFalse(translated instanceof RetriableWriteException);
    }

    @Test
    public void findsTypedExceptionInJdbcNextExceptionChain() {
        SQLException batch = new SQLException("batch failed");
        batch.setNextException(new DataTruncation(1, true, false, 200, 100));

        assertInstanceOf(DataTooLargeException.class, translate(batch));
    }

    @Test
    public void batchUpdateCountsLocateRejectedRowsForPartialIsolation() {
        // 5 行的批次里第 1、3 行被拒；驱动通过 updateCounts 逐行报告结果。
        int[] counts = { 1, Statement.EXECUTE_FAILED, 1, Statement.EXECUTE_FAILED, 1 };
        BatchUpdateException failure = new BatchUpdateException("batch rejected", counts,
                new DataTruncation(1, true, false, 200, 100));

        PartialBatchException partial = assertInstanceOf(PartialBatchException.class, translate(failure));
        assertArrayEquals(new int[] { 1, 3 }, partial.failedIndexes());
        // cause 仍是翻译后的具体原因，刷盘据此判定重试语义。
        assertInstanceOf(DataTooLargeException.class, partial.getCause());
    }

    @Test
    public void wholeBatchFailureIsNotReportedAsLocatable() {
        // 开启 rewriteBatchedStatements 后整批被改写成一条语句，驱动只能把所有行标记失败：
        // 这种报告无法用于隔离，必须退回普通异常让刷盘二分拆批。
        int[] counts = { Statement.EXECUTE_FAILED, Statement.EXECUTE_FAILED };
        BatchUpdateException failure = new BatchUpdateException("rewritten batch rejected", counts,
                new DataTruncation(1, true, false, 200, 100));

        GameJpaException translated = translate(failure);
        assertInstanceOf(DataTooLargeException.class, translated);
        assertFalse(translated instanceof PartialBatchException);
    }

    @Test
    public void fullySuccessfulCountsProduceNoPartialFailure() {
        BatchUpdateException failure = new BatchUpdateException("batch failed", new int[] { 1, 1 },
                new SQLRecoverableException("connection reset"));

        GameJpaException translated = translate(failure);
        assertInstanceOf(ConnectionException.class, translated);
        assertFalse(translated instanceof PartialBatchException);
    }

    private static GameJpaException translate(SQLException failure) {
        return MysqlRdbExecutor.translateSqlException("save", "players", "default", failure);
    }
}
