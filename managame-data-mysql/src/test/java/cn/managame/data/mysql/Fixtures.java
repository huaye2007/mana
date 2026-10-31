package cn.managame.data.mysql;

import cn.managame.data.DataErrorHandler;
import cn.managame.data.DataFailure;
import cn.managame.data.GroupKey;
import cn.managame.data.GroupRepository;
import cn.managame.data.Id;
import cn.managame.data.LogRepository;
import cn.managame.data.MapKey;
import cn.managame.data.SingleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Entities, repositories and an in-memory MySQL-mode H2 database the MySQL tests share. */
final class Fixtures {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Fixtures() {}

    @Table(name = "player")
    static class Player {
        @Id long id;
        String name;
        int level;
        long gold;
        Map<Integer, Long> items = new ConcurrentHashMap<>();   // a JSON column

        Player() {}

        Player(long id, String name) {
            this.id = id;
            this.name = name;
            level = 1;
        }
    }

    @Table(name = "item")
    static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;
        int configId;
        int amount;

        Item() {}

        Item(long uid, long playerId, int slot, int configId, int amount) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
            this.configId = configId;
            this.amount = amount;
        }
    }

    @Table(name = "item_log")
    static class ItemLog {
        long playerId;
        int configId;
        int delta;
        @PartitionKey(type = PartitionType.DAY) long createdAt;

        ItemLog() {}

        ItemLog(long playerId, int configId, int delta, long createdAt) {
            this.playerId = playerId;
            this.configId = configId;
            this.delta = delta;
            this.createdAt = createdAt;
        }
    }

    static final class Players extends SingleRepository<Long, Player> {}

    static final class Items extends GroupRepository<Integer, Item> {}

    static final class ItemLogs extends LogRepository<ItemLog> {}

    /** The application's JSON codec, here over Jackson. */
    static final class Json implements JsonCodec {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override public String encode(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override public Object decode(String json, Type type) {
            try {
                return mapper.readValue(json, mapper.constructType(type));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Collects what the framework gives up on. */
    static final class ReportedFailures implements DataErrorHandler {
        private final List<DataFailure> failures = new ArrayList<>();

        @Override public synchronized void onError(DataFailure failure) { failures.add(failure); }

        synchronized List<DataFailure> all() { return List.copyOf(failures); }
    }

    /** A fresh in-memory database with the tests' tables. */
    static Database database() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:data" + DATABASES.incrementAndGet() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Database database = new Database(h2);
        database.execute("CREATE TABLE player (id BIGINT PRIMARY KEY, name VARCHAR(8), level INT NOT NULL, gold BIGINT NOT NULL, items VARCHAR(4000))");
        database.execute("CREATE TABLE item (uid BIGINT PRIMARY KEY, player_id BIGINT NOT NULL, slot INT NOT NULL, config_id INT NOT NULL,"
                + " amount INT NOT NULL, UNIQUE (player_id, slot))");
        for (String suffix : List.of("", "_20261030", "_20261031"))
            database.execute("CREATE TABLE item_log" + suffix + " (player_id BIGINT, config_id INT, delta INT, created_at BIGINT)");
        return database;
    }

    /**
     * The tests' DataSource: H2, which a test can make refuse connections, as a database that is away does, or fail
     * right after its next commit, as when the acknowledgement of a commit is lost.
     */
    static final class Database implements DataSource {
        private final JdbcDataSource h2;
        volatile boolean refuseConnections;
        volatile boolean failAfterNextCommit;

        Database(JdbcDataSource h2) { this.h2 = h2; }

        void execute(String sql) {
            try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException(sql, e);
            }
        }

        /** The rows of a query, each as its columns' values. */
        List<List<Object>> rows(String sql) {
            try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                List<List<Object>> rows = new ArrayList<>();
                while (result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getObject(i));
                    rows.add(row);
                }
                return rows;
            } catch (SQLException e) {
                throw new IllegalStateException(sql, e);
            }
        }

        @Override public Connection getConnection() throws SQLException {
            if (refuseConnections) throw new SQLTransientConnectionException("the database refuses connections");
            Connection connection = h2.getConnection();
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("commit") && failAfterNextCommit) {
                    failAfterNextCommit = false;
                    connection.commit();
                    throw new SQLRecoverableException("connection lost before the commit was acknowledged");
                }
                try {
                    return method.invoke(connection, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
        }

        @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
