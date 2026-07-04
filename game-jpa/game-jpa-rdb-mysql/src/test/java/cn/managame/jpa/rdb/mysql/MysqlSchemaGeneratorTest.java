package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Id;
import cn.managame.jpa.rdb.annotation.Index;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Version;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MysqlSchemaGeneratorTest {

    @Test
    public void generateOnlyReportsExistingTableDiffsWithoutExecutingSql() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(SchemaDiffPlayer.class);
        FakeDatabase database = new FakeDatabase(true);
        database.columns.add(column("id", "BIGINT", 19, 0, null, DatabaseMetaData.columnNoNulls));
        database.columns.add(column("name", "VARCHAR", 32, 0, "oldbie", DatabaseMetaData.columnNullable));
        database.columns.add(column("level", "INT", 10, 0, "0", DatabaseMetaData.columnNullable));
        database.columns.add(column("version", "BIGINT", 19, 0, "0", DatabaseMetaData.columnNullable));
        database.columns.add(column("legacy", "INT", 10, 0, null, DatabaseMetaData.columnNullable));
        database.indexes.add(index("idx_name", true, (short) 1, "level"));

        List<String> diff = new MysqlSchemaGenerator(new FakeDataSource(database))
                .synchronizeEntity(metadata, MysqlSchemaGenerator.Mode.GENERATE_ONLY);

        assertTrue(diff.contains("ALTER TABLE `schema_diff_player` ADD COLUMN `email` VARCHAR(255)"));
        assertTrue(diff.contains("CREATE UNIQUE INDEX `uk_email` ON `schema_diff_player` (`email`)"));
        assertTrue(contains(diff, "column `name` type differs; expected VARCHAR(64), actual VARCHAR(32)"));
        assertTrue(contains(diff, "column `name` default differs; expected rookie, actual oldbie"));
        assertTrue(contains(diff, "column `level` default differs; expected 1, actual 0"));
        assertTrue(contains(diff, "column `legacy` exists in database but not in entity metadata"));
        assertTrue(contains(diff, "index `idx_name` differs; expected non-unique [name], actual non-unique [level]"));
        assertFalse(database.statementCreated);
    }

    @Test
    public void generateCreateTableUsesGivenPhysicalTableName() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(SchemaDiffPlayer.class);
        MysqlSchemaGenerator generator = new MysqlSchemaGenerator(new FakeDataSource(new FakeDatabase(false)));

        String ddl = generator.generateCreateTable(metadata, "schema_diff_player_20260101");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `schema_diff_player_20260101`"),
                "建表应使用传入的物理表名");

        List<String> indexes = generator.generateIndexes(metadata, "schema_diff_player_20260101");
        assertTrue(indexes.stream().anyMatch(sql -> sql.contains("ON `schema_diff_player_20260101`")),
                "索引应建在物理表上");
        assertTrue(indexes.stream().noneMatch(sql -> sql.contains("ON `schema_diff_player` ")),
                "不应建到逻辑表名上");
    }

    private static boolean contains(List<String> values, String fragment) {
        return values.stream().anyMatch(value -> value.contains(fragment));
    }

    private static Map<String, Object> column(String name, String type, int size, int digits,
            String defaultValue, int nullable) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("COLUMN_NAME", name);
        row.put("TYPE_NAME", type);
        row.put("COLUMN_SIZE", size);
        row.put("DECIMAL_DIGITS", digits);
        row.put("COLUMN_DEF", defaultValue);
        row.put("NULLABLE", nullable);
        return row;
    }

    private static Map<String, Object> index(String name, boolean nonUnique, short ordinal, String column) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("INDEX_NAME", name);
        row.put("NON_UNIQUE", nonUnique);
        row.put("ORDINAL_POSITION", ordinal);
        row.put("COLUMN_NAME", column);
        return row;
    }

    private record FakeDataSource(FakeDatabase database) implements DataSource {
        @Override
        public Connection getConnection() {
            return database.connection();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class FakeDatabase {
        private final boolean tableExists;
        private final List<Map<String, Object>> columns = new ArrayList<>();
        private final List<Map<String, Object>> indexes = new ArrayList<>();
        private boolean statementCreated;

        private FakeDatabase(boolean tableExists) {
            this.tableExists = tableExists;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata();
                        case "createStatement" -> {
                            statementCreated = true;
                            yield statement();
                        }
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private DatabaseMetaData metadata() {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[] { DatabaseMetaData.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTables" -> resultSet(tableExists
                                ? List.of(Map.of("TABLE_NAME", "schema_diff_player"))
                                : List.of());
                        case "getColumns" -> resultSet(columns);
                        case "getIndexInfo" -> resultSet(indexes);
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] { Statement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "execute" -> true;
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        class Cursor {
            private int index = -1;
            private boolean wasNull;
        }
        Cursor cursor = new Cursor();
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++cursor.index < rows.size();
                    case "getString" -> {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.wasNull = value == null;
                        yield value != null ? value.toString() : null;
                    }
                    case "getInt" -> {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.wasNull = value == null;
                        yield value instanceof Number number ? number.intValue() : 0;
                    }
                    case "getShort" -> {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.wasNull = value == null;
                        yield value instanceof Number number ? number.shortValue() : (short) 0;
                    }
                    case "getBoolean" -> {
                        Object value = value(rows, cursor.index, args[0]);
                        cursor.wasNull = value == null;
                        yield switch (value) {
                            case Boolean bool -> bool;
                            case Number number -> number.intValue() != 0;
                            case String text -> Boolean.parseBoolean(text);
                            case null -> false;
                            default -> false;
                        };
                    }
                    case "wasNull" -> cursor.wasNull;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object value(List<Map<String, Object>> rows, int index, Object key) throws SQLException {
        if (index < 0 || index >= rows.size()) {
            throw new SQLException("ResultSet cursor is not positioned on a row");
        }
        return rows.get(index).get(String.valueOf(key));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0F;
        if (returnType == double.class) return 0D;
        return null;
    }

    @Entity
    @Table(name = "schema_diff_player")
    @Index(name = "idx_name", columns = {"name"})
    @Index(name = "uk_email", columns = {"email"}, unique = true)
    private static class SchemaDiffPlayer {
        @Id
        @Column
        private long id;

        @Column(length = 64, defaultValue = "rookie")
        private String name;

        @Column(defaultValue = "1")
        private Integer level;

        @Column
        private String email;

        @Version
        @Column
        private long version;
    }
}
