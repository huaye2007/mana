package cn.managame.data.mysql;

import cn.managame.data.mapper.SchemaMode;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Startup structure check for one entity table, in the {@link SchemaMode} the component was configured with.
 *
 * <p>CREATE is additive and never destructive: it creates a missing table, adds missing columns and creates
 * missing declared indexes, and it never drops, narrows or retypes anything. It is also written for the
 * normal case of several instances of one service starting at once, which is why it does not use
 * {@code CREATE TABLE IF NOT EXISTS}: every statement here can lose a race with another instance running the
 * same statement, the database reports that as "already exists", and the answer is to read the catalog again
 * and validate what the winner created. {@code IF NOT EXISTS} would instead report success without saying
 * whether the structure now there is the one this mapping needs.
 *
 * <p>VALIDATE issues no DDL at all, for a database whose migrations run outside the application: anything
 * missing or too narrow fails the startup that found it. NONE reads nothing, not even the catalog.
 */
final class MysqlSchema {
    private static final System.Logger LOG = System.getLogger(MysqlSchema.class.getName());
    /** Each of these means another instance applied the same structure first; see the class note. */
    private static final int TABLE_EXISTS = 1050, DUPLICATE_COLUMN = 1060, DUPLICATE_INDEX = 1061;
    private static final String TABLE_EXISTS_STATE = "42S01", DUPLICATE_COLUMN_STATE = "42S21";
    private static final Object[] NO_ARGS = new Object[0];
    private static final long[] NO_SIZES = new long[0];
    /** {@code column_type} carries the declared size; {@code data_type} does not. */
    private static final String COLUMNS =
            "SELECT column_name, column_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?";
    private static final String STATISTICS =
            "SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? ORDER BY index_name, seq_in_index";
    private record ColumnInfo(String name, String type) {}
    private record IndexRow(String name, boolean unique, int position, String column) {}
    private MysqlSchema() {}

    static void initialize(MysqlAccess access, MysqlEntityMeta meta, SchemaMode schema) {
        if (Objects.requireNonNull(schema, "schema") == SchemaMode.NONE) return;
        boolean create = schema == SchemaMode.CREATE;
        boolean existing = exists(access, meta.table);
        if (!existing) {
            if (!create) throw conflict(meta.table, "table does not exist and schema is " + schema);
            // Lost the race: the winner's table is an existing table like any other and gets checked.
            existing = !applied(() -> access.update(createTable(meta), NO_ARGS), TABLE_EXISTS, TABLE_EXISTS_STATE);
        }
        if (existing) columns(access, meta, create);
        indexes(access, meta, create);
    }

    private static boolean exists(MysqlAccess access, String table) {
        Long count = access.queryOne("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                new Object[]{table}, row -> row.getLong(1));
        return count != null && count > 0;
    }

    private static String createTable(MysqlEntityMeta meta) {
        return "CREATE TABLE " + SqlNames.quote(meta.table) + " ("
                + meta.fields.stream().map(MysqlFieldMeta::definition).collect(Collectors.joining(", "))
                + ", PRIMARY KEY (" + SqlNames.quote(meta.id.name) + "))";
    }

    /**
     * Validates every column that is already there before adding any, so a mapping that disagrees with the
     * table leaves it completely untouched.
     */
    private static void columns(MysqlAccess access, MysqlEntityMeta meta, boolean create) {
        Map<String, ColumnInfo> actual = read(access, meta.table);
        for (MysqlFieldMeta field : meta.fields) {
            ColumnInfo column = actual.get(key(field.name));
            if (column != null) check(meta.table, field, column);
        }
        for (MysqlFieldMeta field : meta.fields) {
            if (actual.containsKey(key(field.name))) continue;
            if (field == meta.id) throw conflict(meta.table, "existing table has no " + field.name + " primary id column");
            if (!create) throw conflict(meta.table, "column " + field.name + " is missing and schema is " + SchemaMode.VALIDATE);
            if (!applied(() -> access.update("ALTER TABLE " + SqlNames.quote(meta.table) + " ADD COLUMN " + field.definition(), NO_ARGS),
                    DUPLICATE_COLUMN, DUPLICATE_COLUMN_STATE)) {
                ColumnInfo added = read(access, meta.table).get(key(field.name));
                if (added == null) throw conflict(meta.table, "column " + field.name + " is still missing after ADD COLUMN");
                check(meta.table, field, added);
            }
        }
    }

    private static void indexes(MysqlAccess access, MysqlEntityMeta meta, boolean create) {
        Map<String, List<IndexRow>> actual = statistics(access, meta.table);
        List<IndexRow> primary = actual.get("primary");
        if (primary == null || primary.size() != 1 || !primary.getFirst().column().equalsIgnoreCase(meta.id.name))
            throw conflict(meta.table, "primary key must be exactly (" + meta.id.name + ")");
        for (MysqlEntityMeta.IndexMeta index : meta.indexes) {
            List<IndexRow> existing = actual.get(key(index.name()));
            if (existing != null) { match(meta.table, index, existing); continue; }
            if (!create) throw conflict(meta.table, "index " + index.name() + " is missing and schema is " + SchemaMode.VALIDATE);
            if (!applied(() -> access.update(createIndex(meta.table, index), NO_ARGS), DUPLICATE_INDEX, null))
                match(meta.table, index, statistics(access, meta.table).getOrDefault(key(index.name()), List.of()));
        }
        warnUnindexedGroup(meta, actual);
    }

    /**
     * A group load filters on every GroupKey column, all by equality, so it needs an index whose leading columns
     * are exactly those, in any order. Without one each load scans the table. That is not an error — a small
     * table is fine, and GroupKey implies no index (D-STORE) — but it is the usual reason group loads slow down as
     * a table grows, so the startup that can see it says so.
     */
    private static void warnUnindexedGroup(MysqlEntityMeta meta, Map<String, List<IndexRow>> actual) {
        if (meta.groups.isEmpty()) return;
        Set<String> group = meta.groups.stream().map(field -> key(field.name)).collect(Collectors.toSet());
        for (List<IndexRow> rows : actual.values())
            if (leads(group, rows.stream().sorted(Comparator.comparingInt(IndexRow::position)).map(row -> key(row.column())).toList())) return;
        for (MysqlEntityMeta.IndexMeta index : meta.indexes)
            if (leads(group, index.columns().stream().map(MysqlSchema::key).toList())) return;
        String columns = meta.groups.stream().map(field -> field.name).collect(Collectors.joining(","));
        LOG.log(System.Logger.Level.WARNING, "Group loads of " + meta.table + " filter on (" + columns
                + ") and no index starts with those columns, so every group load scans the table;"
                + " declare @Index(columnList = \"" + columns + "\")");
    }

    private static boolean leads(Set<String> group, List<String> columns) {
        return columns.size() >= group.size() && new HashSet<>(columns.subList(0, group.size())).equals(group);
    }

    private static String createIndex(String table, MysqlEntityMeta.IndexMeta index) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + SqlNames.quote(index.name())
                + " ON " + SqlNames.quote(table) + " ("
                + index.columns().stream().map(SqlNames::quote).collect(Collectors.joining(", ")) + ")";
    }

    private static void match(String table, MysqlEntityMeta.IndexMeta index, List<IndexRow> existing) {
        boolean same = !existing.isEmpty() && existing.getFirst().unique() == index.unique()
                && existing.stream().sorted(Comparator.comparingInt(IndexRow::position)).map(row -> key(row.column())).toList()
                        .equals(index.columns().stream().map(MysqlSchema::key).toList());
        if (!same) throw conflict(table, "index " + index.name() + " does not match its declaration");
    }

    private static void check(String table, MysqlFieldMeta field, ColumnInfo actual) {
        if (!compatible(field.sqlType, actual.type()))
            throw conflict(table, "column " + field.name + " expects " + field.sqlType + " but is " + actual.type());
    }

    /**
     * Whether an existing column can hold what this mapping writes: the same base type, and every size it
     * declares at least the expected one. A wider {@code VARCHAR} is therefore accepted and a narrower one is
     * not, which is the case the previous check missed by comparing the base type alone.
     */
    static boolean compatible(String expected, String actual) {
        if (!base(expected).equals(base(actual))) return false;
        long[] want = sizes(expected), have = sizes(actual);
        if (want.length == 0) return true;
        if (have.length < want.length) return false;
        for (int i = 0; i < want.length; i++) if (have[i] < want[i]) return false;
        return true;
    }

    /** The type without its size or trailing attributes: {@code varchar(16)} and {@code int unsigned}. */
    private static String base(String type) {
        String value = type.toLowerCase(Locale.ROOT).trim();
        int end = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(' || c == ' ') { end = i; break; }
        }
        value = value.substring(0, end);
        return value.equals("integer") ? "int" : value;
    }

    /** The numbers a declared type carries — {@code (255)}, {@code (10,2)} — or none when it declares no size. */
    private static long[] sizes(String type) {
        int open = type.indexOf('('), close = open < 0 ? -1 : type.indexOf(')', open);
        if (close < 0) return NO_SIZES;
        List<Long> parsed = new ArrayList<>(2);
        long value = -1;
        for (int i = open + 1; i <= close; i++) {
            char c = type.charAt(i);
            if (c >= '0' && c <= '9') value = (value < 0 ? 0 : value) * 10 + (c - '0');
            else if (c == ',' || c == ')') {
                if (value < 0) return NO_SIZES;   // an empty position is not a size
                parsed.add(value); value = -1;
            } else if (c != ' ') return NO_SIZES; // enum('a','b') and other parenthesised type arguments
        }
        long[] result = new long[parsed.size()];
        for (int i = 0; i < result.length; i++) result[i] = parsed.get(i);
        return result;
    }

    private static Map<String, ColumnInfo> read(MysqlAccess access, String table) {
        Map<String, ColumnInfo> result = new HashMap<>();
        for (ColumnInfo column : access.query(COLUMNS, new Object[]{table},
                row -> new ColumnInfo(row.getString(1), row.getString(2))))
            result.put(key(column.name()), column);
        return result;
    }

    private static Map<String, List<IndexRow>> statistics(MysqlAccess access, String table) {
        return access.query(STATISTICS, new Object[]{table},
                        row -> new IndexRow(row.getString(1), row.getInt(2) == 0, row.getInt(3), row.getString(4)))
                .stream().collect(Collectors.groupingBy(row -> key(row.name())));
    }

    /**
     * Runs one additive statement and reports whether this instance was the one that applied it. False means
     * the database said that structure already exists, which is how losing a race with another instance
     * running the same startup looks; every other failure propagates.
     *
     * @param state the SQLState that also means "already exists", or null when none is specific enough
     */
    private static boolean applied(Runnable statement, int code, String state) {
        try { statement.run(); return true; }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException sql
                        && (sql.getErrorCode() == code || (state != null && state.equals(sql.getSQLState())))) return false;
            throw failure;
        }
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    private static IllegalArgumentException conflict(String table, String detail) {
        return new IllegalArgumentException("Schema conflict on " + table + ": " + detail);
    }
}
