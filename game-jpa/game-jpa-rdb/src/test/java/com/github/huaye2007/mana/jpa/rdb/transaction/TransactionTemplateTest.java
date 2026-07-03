package com.github.huaye2007.mana.jpa.rdb.transaction;

import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.exception.OptimisticLockException;
import com.github.huaye2007.mana.jpa.core.registry.DataSourceRegistry;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class TransactionTemplateTest {

    @Test
    public void restoresOriginalAutoCommitAfterCommit() {
        RecordingDataSource dataSource = new RecordingDataSource(false);
        TransactionTemplate template = new TransactionTemplate(registryOf(dataSource));

        String result = template.execute(() -> "ok");

        assertEquals("ok", result);
        assertEquals(List.of(false, false), dataSource.state.autoCommitChanges);
        assertTrue(dataSource.state.committed);
        assertTrue(dataSource.state.closed);
    }

    @Test
    public void restoresOriginalAutoCommitAfterRollback() {
        RecordingDataSource dataSource = new RecordingDataSource(false);
        TransactionTemplate template = new TransactionTemplate(registryOf(dataSource));

        try {
            template.execute(() -> {
                throw new IllegalStateException("boom");
            });
            fail("Expected transaction failure");
        } catch (GameJpaException expected) {
            assertTrue(expected.getMessage().contains("Transaction failed"));
        }

        assertEquals(List.of(false, false), dataSource.state.autoCommitChanges);
        assertTrue(dataSource.state.rolledBack);
        assertTrue(dataSource.state.closed);
    }

    @Test
    public void preservesGameJpaExceptionTypeAfterRollback() {
        RecordingDataSource dataSource = new RecordingDataSource(false);
        TransactionTemplate template = new TransactionTemplate(registryOf(dataSource));

        try {
            template.execute(() -> {
                throw new OptimisticLockException("player", 1L);
            });
            fail("Expected optimistic lock failure");
        } catch (OptimisticLockException expected) {
            assertEquals("player", expected.entityName());
            assertEquals(1L, expected.entityId());
        }

        assertTrue(dataSource.state.rolledBack);
        assertTrue(dataSource.state.closed);
    }

    @Test
    public void rollsBackAndRethrowsErrors() {
        RecordingDataSource dataSource = new RecordingDataSource(false);
        TransactionTemplate template = new TransactionTemplate(registryOf(dataSource));
        AssertionError error = new AssertionError("boom");

        AssertionError thrown = assertThrows(AssertionError.class, () -> template.execute(() -> {
            throw error;
        }));

        assertSame(error, thrown);
        assertTrue(dataSource.state.rolledBack);
        assertTrue(dataSource.state.closed);
    }

    private static DataSourceRegistry<DataSource> registryOf(DataSource dataSource) {
        DataSourceRegistry<DataSource> registry = new DataSourceRegistry<>();
        registry.registerDefault(dataSource);
        return registry;
    }

    private static class RecordingDataSource implements DataSource {
        private final ConnectionState state;
        private final Connection connection;

        private RecordingDataSource(boolean autoCommit) {
            this.state = new ConnectionState(autoCommit);
            this.connection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> state.autoCommit;
                        case "setAutoCommit" -> {
                            state.autoCommit = (Boolean) args[0];
                            state.autoCommitChanges.add(state.autoCommit);
                            yield null;
                        }
                        case "commit" -> {
                            state.committed = true;
                            yield null;
                        }
                        case "rollback" -> {
                            state.rolledBack = true;
                            yield null;
                        }
                        case "close" -> {
                            state.closed = true;
                            yield null;
                        }
                        case "isClosed" -> state.closed;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap unsupported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static class ConnectionState {
        private boolean autoCommit;
        private boolean committed;
        private boolean rolledBack;
        private boolean closed;
        private final List<Boolean> autoCommitChanges = new ArrayList<>();

        private ConnectionState(boolean autoCommit) {
            this.autoCommit = autoCommit;
        }
    }
}
