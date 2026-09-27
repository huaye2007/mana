package cn.managame.data.mysql;

import java.util.*;
import java.util.stream.Collectors;

final class MysqlSchema {
    private record ColumnInfo(String name, String type, String nullable) {}
    private record IndexRow(String name, boolean unique, int position, String column) {}
    private MysqlSchema() {}
    static void initialize(MysqlAccess access, MysqlEntityMeta meta) {
        Long count = access.queryOne("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                new Object[]{meta.table}, row -> row.getLong(1));
        if (count == null || count == 0) {
            String columns = meta.fields.stream().map(MysqlFieldMeta::definition).collect(Collectors.joining(", "));
            access.update("CREATE TABLE " + SqlNames.quote(meta.table) + " (" + columns
                    + ", PRIMARY KEY (" + SqlNames.quote(meta.id.name) + "))", new Object[0]);
        } else {
            var columns = access.query("SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
                    new Object[]{meta.table}, row -> new ColumnInfo(row.getString(1), row.getString(2), row.getString(3)));
            Map<String,ColumnInfo> actual = new HashMap<>();
            columns.forEach(c -> actual.put(c.name().toLowerCase(Locale.ROOT), c));
            // Validate all existing types before issuing any additive DDL.
            for (var field : meta.fields) {
                ColumnInfo existing = actual.get(field.name.toLowerCase(Locale.ROOT));
                if (existing != null && !compatible(field.sqlType, existing.type()))
                    throw new IllegalArgumentException("Schema type conflict: " + meta.table + "." + field.name);
            }
            for (var field : meta.fields) {
                ColumnInfo c = actual.get(field.name.toLowerCase(Locale.ROOT));
                if (c == null) {
                    if (field == meta.id) throw new IllegalArgumentException("Existing table has no expected primary id: " + meta.table);
                    access.update("ALTER TABLE " + SqlNames.quote(meta.table) + " ADD COLUMN " + field.definition(), new Object[0]);
                } else if (!compatible(field.sqlType, c.type())) {
                    throw new IllegalArgumentException("Schema type conflict: " + meta.table + "." + field.name + " expected " + field.sqlType + " got " + c.type());
                }
            }
        }
        var rows = access.query("SELECT index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? ORDER BY index_name, seq_in_index",
                new Object[]{meta.table}, row -> new IndexRow(row.getString(1), row.getInt(2) == 0, row.getInt(3), row.getString(4)));
        Map<String,List<IndexRow>> actualIndexes = rows.stream().collect(Collectors.groupingBy(r -> r.name().toLowerCase(Locale.ROOT)));
        var primary = actualIndexes.get("primary");
        if (primary == null || primary.size() != 1 || !primary.getFirst().column().equalsIgnoreCase(meta.id.name))
            throw new IllegalArgumentException("Primary key conflict: " + meta.table);
        for (var index : meta.indexes) {
            var existing = actualIndexes.get(index.name().toLowerCase(Locale.ROOT));
            if (existing == null) {
                access.update("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + SqlNames.quote(index.name())
                        + " ON " + SqlNames.quote(meta.table) + " (" + index.columns().stream().map(SqlNames::quote).collect(Collectors.joining(", ")) + ")", new Object[0]);
            } else if (existing.getFirst().unique() != index.unique()
                    || !existing.stream().sorted(Comparator.comparingInt(IndexRow::position)).map(r -> r.column().toLowerCase(Locale.ROOT)).toList()
                    .equals(index.columns().stream().map(c -> c.toLowerCase(Locale.ROOT)).toList()))
                throw new IllegalArgumentException("Index definition conflict: " + index.name());
        }
    }
    private static boolean compatible(String expected, String actual) {
        String e = expected.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "");
        String a = actual.toLowerCase(Locale.ROOT);
        if (a.equals("integer")) a = "int";
        return e.equals(a);
    }
}
