package cn.managame.data.mysql;

import cn.managame.data.codec.*;
import cn.managame.data.meta.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

final class MysqlEntityMeta {
    record IndexMeta(String name, List<String> columns, boolean unique) {}
    final EntityMeta entity;
    final String table, select, selectGroup, insert, update, delete;
    final List<MysqlFieldMeta> fields, updates, groups, updateParameters;
    final List<IndexMeta> indexes;
    final MysqlFieldMeta id;
    final RowMapper<Object> rowMapper;
    MysqlEntityMeta(EntityMeta entity, JsonCodec json, BinaryCodec binary) {
        this.entity = entity;
        Table annotation = entity.entityType().getAnnotation(Table.class);
        if (annotation == null) throw new IllegalArgumentException("@Table required: " + entity.entityType());
        table = SqlNames.identifier(annotation.name());
        Set<Field> identity = entity.identityFields().stream().map(FieldAccessor::field).collect(Collectors.toSet());
        boolean typedJson = entity.fields().stream().anyMatch(f -> f.isAnnotationPresent(Column.class)
                && f.getAnnotation(Column.class).type() == ColumnType.JSON && f.getType() != String.class);
        fields = fields(entity.fields(), identity, json, binary, typedJson ? entity.create() : null);
        Map<Field,MysqlFieldMeta> byField = new HashMap<>();
        fields.forEach(f -> byField.put(f.accessor.field(), f));
        for (Field f : identity) if (!byField.containsKey(f)) throw new IllegalArgumentException("Identity requires @Column: " + f);
        id = byField.get(entity.idField().field());
        groups = entity.groupFields().stream().map(f -> byField.get(f.field())).toList();
        updates = fields.stream().filter(f -> !f.identity).toList();
        var parameters = new ArrayList<>(updates); parameters.add(id);
        updateParameters = List.copyOf(parameters);
        indexes = indexes(annotation, fields, table);
        String tableSql = SqlNames.quote(table);
        String projection = fields.stream().map(f -> SqlNames.quote(f.name)).collect(Collectors.joining(", "));
        select = "SELECT " + projection + " FROM " + tableSql + " WHERE " + SqlNames.quote(id.name) + " = ?";
        selectGroup = "SELECT " + projection + " FROM " + tableSql + " WHERE "
                + groups.stream().map(f -> SqlNames.quote(f.name) + " = ?").collect(Collectors.joining(" AND "));
        insert = insertSql(table, fields);
        delete = "DELETE FROM " + tableSql + " WHERE " + SqlNames.quote(id.name) + " = ?";
        update = updates.isEmpty() ? null : "UPDATE " + tableSql + " SET "
                + updates.stream().map(f -> SqlNames.quote(f.name) + " = ?").collect(Collectors.joining(", "))
                + " WHERE " + SqlNames.quote(id.name) + " = ?";
        rowMapper = row -> {
            Object result = entity.create();
            for (int i = 0; i < fields.size(); i++) fields.get(i).read(result, row, i + 1);
            return result;
        };
    }
    static List<MysqlFieldMeta> fields(List<Field> source, Set<Field> identity, JsonCodec json, BinaryCodec binary) {
        return fields(source, identity, json, binary, null);
    }
    private static List<MysqlFieldMeta> fields(List<Field> source, Set<Field> identity, JsonCodec json, BinaryCodec binary, Object prototype) {
        List<MysqlFieldMeta> result = source.stream().filter(f -> f.isAnnotationPresent(Column.class))
                .map(f -> new MysqlFieldMeta(f, identity.contains(f), json, binary, prototype)).toList();
        Set<String> names = new HashSet<>();
        for (var field : result) if (!names.add(field.name.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Duplicate column: " + field.name);
        if (result.isEmpty()) throw new IllegalArgumentException("No @Column fields");
        return result;
    }
    static List<IndexMeta> indexes(Table annotation, List<MysqlFieldMeta> fields, String table) {
        Set<String> columns = fields.stream().map(f -> f.name).collect(Collectors.toSet());
        Set<String> names = new HashSet<>();
        List<IndexMeta> result = new ArrayList<>();
        for (Index index : annotation.indexes()) {
            List<String> list = Arrays.stream(index.columnList().split(",", -1)).map(String::trim).toList();
            if (!columns.containsAll(list) || new HashSet<>(list).size() != list.size())
                throw new IllegalArgumentException("Invalid index columnList: " + index.columnList());
            String name = index.name().isEmpty() ? SqlNames.generatedIndex("idx_" + table + "_" + String.join("_", list))
                    : SqlNames.identifier(index.name());
            if (name.equalsIgnoreCase("PRIMARY") || !names.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate/reserved index name: " + name);
            result.add(new IndexMeta(name, list, index.unique()));
        }
        return List.copyOf(result);
    }
    static String insertSql(String table, List<MysqlFieldMeta> fields) {
        return "INSERT INTO " + SqlNames.quote(table) + " ("
                + fields.stream().map(f -> SqlNames.quote(f.name)).collect(Collectors.joining(", "))
                + ") VALUES (" + String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
    }
    static Object[] arguments(List<MysqlFieldMeta> fields, Object entity) {
        Object[] result = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) result[i] = fields.get(i).encode(entity);
        return result;
    }
}
