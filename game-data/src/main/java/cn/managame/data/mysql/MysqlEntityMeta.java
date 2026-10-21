package cn.managame.data.mysql;

import cn.managame.data.codec.*;
import cn.managame.data.meta.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class MysqlEntityMeta {
    private static final System.Logger LOG = System.getLogger(MysqlEntityMeta.class.getName());
    record IndexMeta(String name, List<String> columns, boolean unique) {}
    /**
     * Rows one multi-row UPDATE covers at most. Each column picks its value through CASE on the primary key,
     * which costs the server work per row in proportion to the rows in the statement, so a statement stays
     * near this size however large the batch is; see the Java specification §6.2 for the measurement.
     */
    static final int MULTI_UPDATE_ROWS = 100;
    /** MySQL accepts 65,535 placeholders in one prepared statement; a statement stays clear of that. */
    private static final int MAX_PARAMETERS = 60_000;
    final EntityMeta entity;
    final String table, select, selectGroup, insert, update, delete;
    final List<MysqlFieldMeta> fields, updates, groups, updateParameters;
    final List<IndexMeta> indexes;
    final MysqlFieldMeta id;
    final RowMapper<Object> rowMapper;
    /** Rows per multi-row UPDATE for this table: MULTI_UPDATE_ROWS, fewer for a table wide enough to need it. */
    final int updateRows;
    private final Map<Integer, String> multiUpdates = new ConcurrentHashMap<>();
    MysqlEntityMeta(EntityMeta entity, JsonCodec json, BinaryCodec binary) {
        this.entity = entity;
        Table annotation = entity.entityType().getAnnotation(Table.class);
        if (annotation == null) throw new IllegalArgumentException("@Table required: " + entity.entityType());
        table = SqlNames.identifier(annotation.name());
        Set<Field> identity = entity.identityFields().stream().map(FieldAccessor::field).collect(Collectors.toSet());
        // One instance, so a JSON field decodes back into the type its initializer chose (ConcurrentHashMap, say).
        Object prototype = entity.create();
        fields = fields(entity.fields(), identity, json, binary, prototype);
        warnUnsafeCollections(entity, fields, prototype);
        Map<Field,MysqlFieldMeta> byField = new HashMap<>();
        fields.forEach(f -> byField.put(f.accessor.field(), f));
        for (Field f : identity) if (!byField.containsKey(f)) throw new IllegalArgumentException("Identity field must be saved, not transient: " + f);
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
        updateRows = updates.isEmpty() ? 0 : updateRows(updates.size());
        rowMapper = row -> {
            Object result = entity.create();
            for (int i = 0; i < fields.size(); i++) fields.get(i).read(result, row, i + 1);
            return result;
        };
    }
    static List<MysqlFieldMeta> fields(List<Field> source, Set<Field> identity, JsonCodec json, BinaryCodec binary) {
        return fields(source, identity, json, binary, null);
    }
    /**
     * The save thread encodes an entity while Routes keep changing it (D-OWN-01), so a collection it iterates has
     * to be thread-safe, and a JSON column decodes back into the type the field was initialized with. A field left
     * as a plain java.util collection — or not initialized, which a load fills with one — is named here at startup
     * instead of failing later as a concurrent modification under load.
     */
    private static void warnUnsafeCollections(EntityMeta entity, List<MysqlFieldMeta> fields, Object prototype) {
        for (MysqlFieldMeta field : fields) {
            Class<?> declared = field.accessor.type();
            boolean map = Map.class.isAssignableFrom(declared);
            if (!map && !Collection.class.isAssignableFrom(declared)) continue;
            Object value = field.accessor.get(prototype);
            if (value != null && threadSafe(value.getClass())) continue;
            LOG.log(System.Logger.Level.WARNING, entity.entityType().getSimpleName() + "." + field.accessor.field().getName()
                    + (value == null ? " is not initialized, so a load fills it with a non-thread-safe collection"
                                     : " is a " + value.getClass().getName())
                    + ", which the save thread may read while a Route changes it; initialize it with a thread-safe"
                    + " collection such as " + (map ? "ConcurrentHashMap" : "CopyOnWriteArrayList") + " (D-OWN-01)");
        }
    }
    private static boolean threadSafe(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.concurrent.") || name.startsWith("java.util.Collections$Synchronized")
                || name.startsWith("java.util.Collections$Unmodifiable") || name.startsWith("java.util.ImmutableCollections$");
    }
    /** Every persistent field is a column (D-MAP-01); {@link Column} only adjusts one. */
    private static List<MysqlFieldMeta> fields(List<Field> source, Set<Field> identity, JsonCodec json, BinaryCodec binary, Object prototype) {
        List<MysqlFieldMeta> result = source.stream().filter(EntityMeta::persistent)
                .map(f -> new MysqlFieldMeta(f, identity.contains(f), json, binary, prototype)).toList();
        Set<String> names = new HashSet<>();
        for (var field : result) if (!names.add(field.name.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Duplicate column: " + field.name);
        if (result.isEmpty()) throw new IllegalArgumentException("No saved fields: every field is static or transient");
        return result;
    }
    /**
     * One UPDATE for {@code rows} rows: every non-identity column takes its value by primary key through CASE,
     * and WHERE lists the keys, so the statement matches exactly the rows that exist and its update count says
     * whether any is missing. Plain SQL, cached per row count. Arguments come from {@link #multiUpdateArguments}.
     */
    /** Each row of a multi-row UPDATE binds two placeholders per updated column and one in WHERE. */
    static int updateRows(int columns) {
        return Math.max(1, Math.min(MULTI_UPDATE_ROWS, MAX_PARAMETERS / (2 * columns + 1)));
    }
    String multiUpdate(int rows) {
        return multiUpdates.computeIfAbsent(rows, n -> {
            String key = SqlNames.quote(id.name);
            String cases = String.join(" ", Collections.nCopies(n, "WHEN ? THEN ?"));
            return "UPDATE " + SqlNames.quote(table) + " SET "
                    + updates.stream().map(f -> SqlNames.quote(f.name) + " = CASE " + key + " " + cases + " END")
                            .collect(Collectors.joining(", "))
                    + " WHERE " + key + " IN (" + String.join(", ", Collections.nCopies(n, "?")) + ")";
        });
    }
    /** Each entity is encoded once, so all of a row's columns come from the same moment. */
    Object[] multiUpdateArguments(List<?> entities) {
        int rows = entities.size(), columns = updates.size();
        Object[] ids = new Object[rows];
        Object[][] values = new Object[rows][];
        for (int r = 0; r < rows; r++) { ids[r] = id.encode(entities.get(r)); values[r] = arguments(updates, entities.get(r)); }
        Object[] result = new Object[columns * 2 * rows + rows];
        int i = 0;
        for (int c = 0; c < columns; c++)
            for (int r = 0; r < rows; r++) { result[i++] = ids[r]; result[i++] = values[r][c]; }
        for (int r = 0; r < rows; r++) result[i++] = ids[r];
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
