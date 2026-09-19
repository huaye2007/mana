package cn.managame.rdb;

import cn.managame.annotation.*;
import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.rdb.dialect.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultipleIndexesTest {
    public static class Base {
        @Id public long id;
        @Column("player_id") public long playerId;
        public int slot;
    }
    @Table(value = "bag", indexes = {
            @Index(name = "uk_player_slot", columnList = " player_id ASC, slot desc ", unique = true),
            @Index(columnList = "slot")})
    public static class Bag extends Base { }
    @Table(indexes = @Index(columnList = "playerId")) public static class Unknown extends Base { }
    @Table(indexes = @Index(columnList = "slot,slot")) public static class Repeated extends Base { }
    @Table(indexes = @Index(columnList = "slot RANDOM")) public static class Direction extends Base { }
    @Table(indexes = @Index(columnList = "slot,")) public static class Empty extends Base { }
    @Table(indexes = {@Index(name = "same", columnList = "slot"), @Index(name = "SAME", columnList = "id")})
    public static class Duplicate extends Base { }
    @CompoundIndex(def = "{player_id:1,slot:1}") public static class MongoOnly extends Base { }

    @Test void orderedPhysicalColumnsGeneratePortableSqlAndNames() {
        var metadata = EntityMetadata.inspect(Bag.class);
        var index = metadata.indexes().getFirst();
        assertEquals(List.of("playerId", "slot"), index.columns().stream().map(c -> c.fieldName()).toList());
        assertEquals("idx_bag_slot", metadata.indexes().get(1).name());
        assertEquals("CREATE UNIQUE INDEX `uk_player_slot` ON `bag` (`player_id` ASC, `slot` DESC)",
                new MySqlDialect().createIndexSql(metadata, index));
        assertEquals("CREATE UNIQUE INDEX \"uk_player_slot\" ON \"bag\" (\"player_id\" ASC, \"slot\" DESC)",
                new PostgreSqlDialect().createIndexSql(metadata, index));
        assertEquals(new MySqlDialect().createIndexSql(metadata, index), new MariaDbDialect().createIndexSql(metadata, index));
    }
    @Test void invalidDeclarationsFailAtMetadataInitialization() {
        for (Class<?> type : List.of(Unknown.class, Repeated.class, Direction.class, Empty.class, Duplicate.class)) {
            assertThrows(DataException.class, () -> EntityMetadata.inspect(type), type.getSimpleName());
        }
    }
    @Test void createsAllMissingIndexesAndAcceptsConcurrentCreation() {
        for (boolean concurrent : new boolean[]{false, true}) {
            var jdbc = new SchemaJdbc(); jdbc.concurrent = concurrent;
            var issues = new ArrayList<RdbSchemaIssue>();
            new RdbSchemaManager(jdbc.source(), new MySqlDialect(), issues::add).ensure(EntityMetadata.inspect(Bag.class));
            assertEquals(List.of("CREATE UNIQUE INDEX `uk_player_slot` ON `bag` (`player_id` ASC, `slot` DESC)",
                    "CREATE INDEX `idx_bag_slot` ON `bag` (`slot` ASC)"), jdbc.ddl.stream().filter(s -> s.startsWith("CREATE ") && s.contains("INDEX")).toList());
            assertTrue(issues.isEmpty(), issues.toString());
        }
    }
    @Test void reportsChangedOrderDirectionAndUniquenessWithoutReplacingIndexes() {
        for (String mismatch : List.of("order", "direction", "unique", "count")) {
            var jdbc = new SchemaJdbc(); jdbc.install("uk_player_slot"); jdbc.install("idx_bag_slot");
            if (mismatch.equals("order")) {
                jdbc.indexRows.get(0).put("ORDINAL_POSITION", 2); jdbc.indexRows.get(1).put("ORDINAL_POSITION", 1);
            } else if (mismatch.equals("direction")) jdbc.indexRows.get(1).put("ASC_OR_DESC", "A");
            else if (mismatch.equals("unique")) jdbc.indexRows.get(0).put("NON_UNIQUE", true);
            else jdbc.indexRows.remove(1);
            var issues = new ArrayList<RdbSchemaIssue>();
            new RdbSchemaManager(jdbc.source(), new MySqlDialect(), issues::add).ensure(EntityMetadata.inspect(Bag.class));
            assertEquals(1, issues.size(), mismatch);
            assertEquals(RdbSchemaIssue.Kind.INDEX_DEFINITION, issues.getFirst().kind());
            assertTrue(jdbc.ddl.stream().noneMatch(s -> s.contains("INDEX")));
        }
    }
    @Test void jdbcRejectsMongoJsonIndexesInsteadOfSilentlyIgnoringThem() {
        var jdbc = new SchemaJdbc();
        assertThrows(DataException.class, () -> new RdbSchemaManager(jdbc.source(), new MySqlDialect())
                .ensure(EntityMetadata.inspect(MongoOnly.class)));
        assertTrue(jdbc.ddl.isEmpty());
    }

    private static final class SchemaJdbc {
        final List<String> ddl = new ArrayList<>();
        final List<Map<String, Object>> indexRows = new ArrayList<>();
        boolean concurrent;
        void install(String name) {
            if (name.equals("uk_player_slot")) {
                indexRows.add(indexRow(name, "player_id", 1, "A", false));
                indexRows.add(indexRow(name, "slot", 2, "D", false));
            } else indexRows.add(indexRow(name, "slot", 1, "A", true));
        }
        DataSource source() {
            var metadata = proxy(DatabaseMetaData.class, (p, m, a) -> switch (m.getName()) {
                case "getColumns" -> rows(List.of(column("id", "BIGINT"), column("player_id", "BIGINT"), column("slot", "INT")));
                case "getIndexInfo" -> rows(indexRows);
                case "getPrimaryKeys" -> rows(List.of(Map.of("COLUMN_NAME", "id")));
                default -> throw new AssertionError(m);
            });
            return proxy(DataSource.class, (p, m, a) -> {
                if (!m.getName().equals("getConnection")) throw new AssertionError(m);
                return proxy(Connection.class, (c, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "getCatalog" -> "game";
                    case "close" -> null;
                    case "createStatement" -> proxy(Statement.class, (s, call, values) -> switch (call.getName()) {
                        case "close" -> null;
                        case "execute" -> {
                            String sql = (String) values[0]; ddl.add(sql);
                            if (sql.contains("INDEX")) {
                                install(sql.contains("uk_player_slot") ? "uk_player_slot" : "idx_bag_slot");
                                if (concurrent) throw new SQLException("created by another server");
                            }
                            yield false;
                        }
                        default -> throw new AssertionError(call);
                    });
                    default -> throw new AssertionError(method);
                });
            });
        }
    }
    private static Map<String, Object> column(String name, String type) {
        return Map.of("COLUMN_NAME", name, "TYPE_NAME", type, "COLUMN_SIZE", 0);
    }
    private static Map<String, Object> indexRow(String name, String column, int ordinal, String direction, boolean nonUnique) {
        return new HashMap<>(Map.of("INDEX_NAME", name, "COLUMN_NAME", column, "ORDINAL_POSITION", ordinal,
                "ASC_OR_DESC", direction, "NON_UNIQUE", nonUnique));
    }
    private static ResultSet rows(List<Map<String, Object>> values) {
        int[] position = {-1};
        return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "next" -> ++position[0] < values.size();
            case "getString", "getInt", "getBoolean", "getObject" -> values.get(position[0]).get((String) a[0]);
            case "close" -> null;
            default -> throw new AssertionError(m);
        });
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
