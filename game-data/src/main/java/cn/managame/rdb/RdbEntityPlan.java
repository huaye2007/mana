package cn.managame.rdb;

import cn.managame.core.metadata.EntityMetadata;

import java.util.stream.Collectors;

final class RdbEntityPlan {
    final String selectById;
    final String insert;
    private final String insertSuffix;
    final String update;
    final String delete;
    final String deleteGroup;

    RdbEntityPlan(EntityMetadata<?> metadata, RdbDialect dialect) {
        this(metadata, dialect, null);
    }

    RdbEntityPlan(EntityMetadata<?> metadata, RdbDialect dialect, String physicalName) {
        String table = dialect.qualifiedTable(metadata, physicalName);
        String id = dialect.quote(metadata.idProperty().rdbName());
        String columns = metadata.properties().stream()
                .map(p -> dialect.quote(p.rdbName()))
                .collect(Collectors.joining(","));
        String placeholders = metadata.properties().stream().map(p -> "?").collect(Collectors.joining(","));
        String set = metadata.properties().stream()
                .filter(p -> !p.id())
                .map(p -> dialect.quote(p.rdbName()) + "=?")
                .collect(Collectors.joining(","));

        this.selectById = "SELECT " + columns + " FROM " + table + " WHERE " + id + "=?";
        this.insertSuffix = " (" + columns + ") VALUES (" + placeholders + ")";
        this.insert = insertInto(table);
        this.update = "UPDATE " + table + " SET " + set + " WHERE " + id + "=?";
        this.delete = "DELETE FROM " + table + " WHERE " + id + "=?";
        this.deleteGroup = metadata.groupKeyProperties().isEmpty() ? null
                : "DELETE FROM " + table + " WHERE " + metadata.groupKeyProperties().stream()
                .map(p -> dialect.quote(p.rdbName()) + "=?")
                .collect(Collectors.joining(" AND "));
    }
    String insertInto(String qualifiedTable) { return "INSERT INTO " + qualifiedTable + insertSuffix; }
}
