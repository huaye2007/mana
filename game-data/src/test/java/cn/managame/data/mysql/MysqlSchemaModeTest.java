package cn.managame.data.mysql;

import cn.managame.data.annotation.Id;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What each {@link SchemaMode} may do to storage structure, and how a startup that loses a race with another
 * instance of the same service ends. The catalog is scripted rather than a real database, because these
 * branches otherwise need two instances reaching the same statement in the same millisecond.
 */
class MysqlSchemaModeTest {
    @Table(name = "players", indexes = @Index(columnList = "name"))
    static class Player { @Id @Column long id; @Column String name; }

    private static final String INDEX = "idx_players_name";

    /** What catalog reads return, what each statement is recorded as, and which statement fails how. */
    static final class Catalog implements MysqlAccess {
        final Map<String, String> columns = new LinkedHashMap<>();   // column_name -> column_type
        final List<Object[]> statistics = new ArrayList<>();         // index_name, non_unique, seq, column
        final List<String> ddl = new ArrayList<>(), reads = new ArrayList<>();
        boolean table;
        /** Statement prefix to the failure it raises: the race this catalog reproduces. */
        final Map<String, SQLException> failures = new LinkedHashMap<>();
        /** What the instance that won that race left behind; applied when the statement fails. */
        Runnable winner = () -> {};

        Catalog table() { table = true; statistics.add(new Object[]{"PRIMARY", 0, 1, "id"}); return this; }
        Catalog column(String name, String type) { columns.put(name, type); return this; }
        Catalog index(String name, boolean unique, String... names) {
            for (int i = 0; i < names.length; i++) statistics.add(new Object[]{name, unique ? 0 : 1, i + 1, names[i]});
            return this;
        }
        Catalog fails(String prefix, int code, String state) {
            failures.put(prefix, new SQLException("scripted", state, code)); return this;
        }

        @Override public <T> T queryOne(String sql, Object[] args, RowMapper<T> mapper) {
            reads.add(sql);
            if (sql.contains("information_schema.tables")) return MysqlMappingTest.row(mapper, table ? 1L : 0L);
            throw new UnsupportedOperationException(sql);
        }
        @Override public <T> List<T> query(String sql, Object[] args, RowMapper<T> mapper) {
            reads.add(sql);
            if (sql.contains("information_schema.columns"))
                return columns.entrySet().stream().map(c -> MysqlMappingTest.row(mapper, c.getKey(), c.getValue())).toList();
            if (sql.contains("information_schema.statistics"))
                return statistics.stream().map(row -> MysqlMappingTest.row(mapper, row)).toList();
            throw new UnsupportedOperationException(sql);
        }
        @Override public int update(String sql, Object[] args) {
            ddl.add(sql);
            for (var failure : failures.entrySet())
                if (sql.startsWith(failure.getKey())) { winner.run(); throw new MysqlException(sql, failure.getValue()); }
            return 1;
        }
        @Override public int[] batchUpdate(String sql, List<Object[]> args) { throw new UnsupportedOperationException(sql); }
        @Override public <T> T transaction(TransactionAction<T> action) { throw new UnsupportedOperationException("transaction"); }
    }

    private static void initialize(MysqlAccess access, SchemaMode schema) {
        new MysqlEntityMapper(access).initialize(new EntityMeta(Player.class, EntityMeta.Kind.SINGLE), schema);
    }

    @Test void createWritesTheTableAndItsDeclaredIndexAndChecksNoColumnItJustCreated() {
        var catalog = new Catalog();
        catalog.statistics.add(new Object[]{"PRIMARY", 0, 1, "id"});   // what the catalog shows once it exists
        initialize(catalog, SchemaMode.CREATE);
        assertEquals(2, catalog.ddl.size(), catalog.ddl.toString());
        assertTrue(catalog.ddl.getFirst().startsWith("CREATE TABLE `players`"), catalog.ddl.getFirst());
        assertTrue(catalog.ddl.getFirst().contains("`name` VARCHAR(255)"), catalog.ddl.getFirst());
        assertTrue(catalog.ddl.get(1).startsWith("CREATE INDEX `" + INDEX + "`"), catalog.ddl.get(1));
        // A table this startup created is the mapping; reading its columns back would prove nothing.
        assertTrue(catalog.reads.stream().noneMatch(sql -> sql.contains("information_schema.columns")), catalog.reads.toString());
    }

    @Test void createAddsOnlyTheMissingColumnAndNeitherDropsNorRetypesAnything() {
        var catalog = new Catalog().table().column("id", "bigint(20)").index(INDEX, false, "name");
        initialize(catalog, SchemaMode.CREATE);
        assertEquals(1, catalog.ddl.size(), catalog.ddl.toString());
        assertTrue(catalog.ddl.getFirst().startsWith("ALTER TABLE `players` ADD COLUMN `name`"), catalog.ddl.getFirst());
        assertTrue(catalog.ddl.stream().noneMatch(sql -> sql.contains("DROP") || sql.contains("CHANGE") || sql.contains("MODIFY")));
    }

    @Test void validateIssuesNoDdlAndNamesWhatIsMissing() {
        var absent = new Catalog();
        assertTrue(message(absent, SchemaMode.VALIDATE).contains("table does not exist"));
        assertTrue(absent.ddl.isEmpty());

        var column = new Catalog().table().column("id", "bigint").index(INDEX, false, "name");
        assertTrue(message(column, SchemaMode.VALIDATE).contains("column name is missing"));
        assertTrue(column.ddl.isEmpty());

        var index = new Catalog().table().column("id", "bigint").column("name", "varchar(255)");
        assertTrue(message(index, SchemaMode.VALIDATE).contains("index " + INDEX + " is missing"));
        assertTrue(index.ddl.isEmpty());

        var complete = new Catalog().table().column("id", "bigint").column("name", "varchar(255)").index(INDEX, false, "name");
        assertDoesNotThrow(() -> initialize(complete, SchemaMode.VALIDATE));
        assertTrue(complete.ddl.isEmpty());
    }

    @Test void noneReadsNoCatalogAtAll() {
        var catalog = new Catalog();
        initialize(catalog, SchemaMode.NONE);
        assertTrue(catalog.reads.isEmpty());
        assertTrue(catalog.ddl.isEmpty());
    }

    @Test void aColumnTooNarrowForWhatTheMappingWritesIsRejectedByEveryModeThatLooks() {
        for (SchemaMode mode : List.of(SchemaMode.CREATE, SchemaMode.VALIDATE)) {
            var narrow = new Catalog().table().column("id", "bigint").column("name", "varchar(16)").index(INDEX, false, "name");
            assertTrue(message(narrow, mode).contains("column name expects VARCHAR(255) but is varchar(16)"));
            assertTrue(narrow.ddl.isEmpty(), "a mapping that disagrees with the table changes nothing");
        }
        // Wider holds what the mapping writes, and a display width is not a size the mapping asked for.
        var wide = new Catalog().table().column("id", "bigint(20)").column("name", "varchar(1024)").index(INDEX, false, "name");
        assertDoesNotThrow(() -> initialize(wide, SchemaMode.VALIDATE));
    }

    @Test void losingTheCreateRaceChecksTheWinnersTableInsteadOfFailingTheStartup() {
        var matching = new Catalog().fails("CREATE TABLE", 1050, "42S01");
        matching.winner = () -> matching.table().column("id", "bigint").column("name", "varchar(255)").index(INDEX, false, "name");
        assertDoesNotThrow(() -> initialize(matching, SchemaMode.CREATE));
        assertEquals(1, matching.ddl.size(), "the winner's table already matches, so nothing else is issued");
        assertTrue(matching.reads.stream().anyMatch(sql -> sql.contains("information_schema.columns")));

        var conflicting = new Catalog().fails("CREATE TABLE", 1050, "42S01");
        conflicting.winner = () -> conflicting.table().column("id", "bigint").column("name", "varchar(16)").index(INDEX, false, "name");
        assertTrue(message(conflicting, SchemaMode.CREATE).contains("varchar(16)"));
    }

    @Test void losingTheAddColumnOrCreateIndexRaceAlsoChecksWhatTheWinnerLeft() {
        var added = new Catalog().table().column("id", "bigint").index(INDEX, false, "name").fails("ALTER TABLE", 1060, "42S21");
        added.winner = () -> added.column("name", "varchar(255)");
        assertDoesNotThrow(() -> initialize(added, SchemaMode.CREATE));

        var narrow = new Catalog().table().column("id", "bigint").index(INDEX, false, "name").fails("ALTER TABLE", 1060, "42S21");
        narrow.winner = () -> narrow.column("name", "varchar(16)");
        assertTrue(message(narrow, SchemaMode.CREATE).contains("varchar(16)"));

        var indexed = new Catalog().table().column("id", "bigint").column("name", "varchar(255)").fails("CREATE INDEX", 1061, null);
        indexed.winner = () -> indexed.index(INDEX, false, "name");
        assertDoesNotThrow(() -> initialize(indexed, SchemaMode.CREATE));

        var unique = new Catalog().table().column("id", "bigint").column("name", "varchar(255)").fails("CREATE INDEX", 1061, null);
        unique.winner = () -> unique.index(INDEX, true, "name");   // unique where the mapping declares a plain index
        assertTrue(message(unique, SchemaMode.CREATE).contains("does not match its declaration"));
    }

    @Test void aFailureThatIsNotDuplicateStructureStillFailsTheStartup() {
        // 1142 ER_TABLEACCESS_DENIED_ERROR: an account without DDL rights must not look like a won race.
        var denied = new Catalog().fails("CREATE TABLE", 1142, "42000");
        assertThrows(MysqlException.class, () -> initialize(denied, SchemaMode.CREATE));
    }

    @Table(name = "items")
    static class Item { @Id @Column long id; @cn.managame.data.annotation.GroupKey @Column long roleId;
        @cn.managame.data.annotation.MapKey @Column long itemId; }
    @Table(name = "items", indexes = @Index(columnList = "role_id,item_id"))
    static class IndexedItem { @Id @Column long id; @cn.managame.data.annotation.GroupKey @Column long roleId;
        @cn.managame.data.annotation.MapKey @Column long itemId; }

    /** The warnings MysqlSchema logs while {@code initialize} runs. */
    private static List<String> warnings(Class<?> entity, Catalog catalog) {
        var logger = java.util.logging.Logger.getLogger(MysqlSchema.class.getName());
        var warnings = new ArrayList<String>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) warnings.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try { new MysqlEntityMapper(catalog).initialize(new EntityMeta(entity, EntityMeta.Kind.GROUP), SchemaMode.VALIDATE); }
        finally { logger.removeHandler(handler); }
        return warnings;
    }

    @Test void aGroupWithoutAnIndexOnItsKeysIsNamedAtStartup() {
        Catalog bare = new Catalog().table().column("id", "bigint").column("role_id", "bigint").column("item_id", "bigint");
        List<String> logged = warnings(Item.class, bare);
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.getFirst().contains("declare @Index(columnList = \"role_id\")"), logged.getFirst());
        // An index created outside the mapping counts, in any column order behind the group keys.
        Catalog external = new Catalog().table().column("id", "bigint").column("role_id", "bigint").column("item_id", "bigint")
                .index("by_role", false, "role_id", "item_id");
        assertEquals(List.of(), warnings(Item.class, external));
        Catalog declared = new Catalog().table().column("id", "bigint").column("role_id", "bigint").column("item_id", "bigint")
                .index("idx_items_role_id_item_id", false, "role_id", "item_id");
        assertEquals(List.of(), warnings(IndexedItem.class, declared));
    }

    private static String message(MysqlAccess access, SchemaMode schema) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> initialize(access, schema));
        assertTrue(failure.getMessage().startsWith("Schema conflict on players:"), failure.getMessage());
        return failure.getMessage();
    }
}
