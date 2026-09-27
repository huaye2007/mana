package cn.managame.data.mysql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class JdbcMysqlAccessTest {
    JdbcMysqlAccess access;
    @BeforeEach void setup() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        access = new JdbcMysqlAccess(source);
        access.update("CREATE TABLE entry (id BIGINT PRIMARY KEY, value_text VARCHAR(255))",new Object[0]);
    }
    @Test void queryCardinalityNullsAndBatchBinding() {
        assertNull(access.queryOne("SELECT * FROM entry",new Object[0],r -> r.getLong(1)));
        access.batchUpdate("INSERT INTO entry VALUES (?,?)",List.of(new Object[]{1L,null},new Object[]{2L,'x'}));
        assertEquals(2,access.query("SELECT * FROM entry",new Object[0],r -> r.getLong(1)).size());
        assertThrows(MysqlException.class, () -> access.queryOne("SELECT * FROM entry",new Object[0],r -> r.getLong(1)));
        assertNull(access.queryOne("SELECT value_text FROM entry WHERE id=?",new Object[]{1L},r -> r.getString(1)));
        assertEquals("x",access.queryOne("SELECT value_text FROM entry WHERE id=?",new Object[]{2L},r -> r.getString(1)));
    }
    @Test void transactionCommitsRollsBackAndCannotEscapeCallback() {
        var captured = new AtomicReference<MysqlTransaction>();
        access.transaction(tx -> { captured.set(tx); tx.update("INSERT INTO entry VALUES (?,?)",new Object[]{1L,"old"}); return null; });
        assertThrows(IllegalStateException.class, () -> captured.get().query("SELECT * FROM entry",new Object[0],r -> r.getLong(1)));
        assertThrows(MysqlException.class, () -> access.transaction(tx -> {
            tx.update("DELETE FROM entry WHERE id=?",new Object[]{1L});
            tx.batchUpdate("INSERT INTO entry VALUES (?,?)",List.of(new Object[]{1L,"new"},new Object[]{1L,"duplicate"}));
            return null;
        }));
        assertEquals("old",access.queryOne("SELECT value_text FROM entry WHERE id=1",new Object[0],r -> r.getString(1)));
        assertThrows(MysqlException.class, () -> access.transaction(tx -> {
            tx.update("DELETE FROM entry",new Object[0]); throw new Exception("checked");
        }));
        assertEquals(Long.valueOf(1),access.<Long>queryOne("SELECT COUNT(*) FROM entry",new Object[0],r -> r.getLong(1)));
    }
    @Test void sqlExceptionPreservesCauseAndSqlState() {
        var failure = assertThrows(MysqlException.class, () -> access.update("INVALID SQL",new Object[0]));
        assertEquals("INVALID SQL",failure.sql()); assertNotNull(failure.sqlState());
        assertInstanceOf(java.sql.SQLException.class,failure.getCause());
    }
}
