package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.exception.ConcurrentWriteException;
import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DataTooLargeException;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.exception.WriteTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.DataTruncation;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;

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

    private static GameJpaException translate(SQLException failure) {
        return MysqlRdbExecutor.translateSqlException("save", "players", "default", failure);
    }
}
