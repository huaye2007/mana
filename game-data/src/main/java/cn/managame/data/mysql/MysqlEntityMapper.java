package cn.managame.data.mysql;

import java.sql.SQLException;
import cn.managame.data.codec.*;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MysqlEntityMapper implements EntityMapper {
    private final MysqlAccess access;
    private final JsonCodec json;
    private final BinaryCodec binary;
    private final Map<EntityMeta,MysqlEntityMeta> metas = new ConcurrentHashMap<>();
    public MysqlEntityMapper(MysqlAccess access) { this(access, null, null); }
    public MysqlEntityMapper(MysqlAccess access, JsonCodec json, BinaryCodec binary) {
        this.access = Objects.requireNonNull(access); this.json = json; this.binary = binary;
    }
    @Override public void initialize(EntityMeta meta, SchemaMode schema) {
        metas.computeIfAbsent(meta, key -> {
            MysqlEntityMeta result = new MysqlEntityMeta(key, json, binary);
            MysqlSchema.initialize(access, result, schema);
            // Startup already talks to the database here, so a slow batch setting is named now rather than at the
            // first save; under NONE that first save is where it is found.
            if (schema != SchemaMode.NONE) access.supportsMultiRowUpdate();
            return result;
        });
    }
    private MysqlEntityMeta meta(EntityMeta key) {
        var result = metas.get(key);
        if (result == null) throw new IllegalStateException("Mapper not initialized");
        return result;
    }
    @Override public Object load(EntityMeta key, Object id) {
        var m = meta(key); return access.queryOne(m.select, new Object[]{id}, m.rowMapper);
    }
    @Override public List<?> loadGroup(EntityMeta key, GroupKeys group) {
        key.validateGroupKey(group); var m = meta(key);
        Object[] args = new Object[group.size()];
        for (int i = 0; i < args.length; i++) args[i] = group.valueAt(i);
        return access.query(m.selectGroup, args, m.rowMapper);
    }
    // Every batch runs in one transaction: a failed batch leaves no partial rows, so it can be retried
    // or split into single rows without duplicate-key failures. Encoding happens before the transaction.
    @Override public void insertBatch(EntityMeta key, List<?> entities) {
        var m = meta(key); var rows = entities.stream().map(e -> MysqlEntityMeta.arguments(m.fields, e)).toList();
        access.supportsMultiRowUpdate();   // under NONE the first save is the first look at the database
        access.transaction(tx -> tx.batchUpdate(m.insert, rows));
    }
    /**
     * An UPDATE matching no row fails: the row is missing, so silently succeeding would lose the change.
     *
     * <p>On MySQL a batch is sent as multi-row UPDATE statements of {@code updateRows} rows each, one round trip
     * per statement instead of one per row — UPDATE is the statement the driver cannot fold the way it folds
     * INSERT. A statement that matches fewer rows than it names fails the batch, which the pipeline then splits
     * into single rows, so the missing row is still the one reported.
     */
    @Override public void updateBatch(EntityMeta key, List<?> entities) {
        var m = meta(key); if (m.update == null) return;
        if (access.supportsMultiRowUpdate()) {
            List<Object[]> statements = new ArrayList<>();
            for (int from = 0; from < entities.size(); from += m.updateRows)
                statements.add(m.multiUpdateArguments(entities.subList(from, Math.min(entities.size(), from + m.updateRows))));
            access.transaction(tx -> {
                for (Object[] arguments : statements) {
                    int rows = (arguments.length) / (2 * m.updates.size() + 1);
                    String sql = m.multiUpdate(rows);
                    int matched = tx.update(sql, arguments);
                    if (matched != rows) throw new MysqlException(sql, new SQLException(
                            "UPDATE matched " + matched + " of " + rows + " rows", "02000"));
                }
                return null;
            });
            return;
        }
        var rows = entities.stream().map(e -> MysqlEntityMeta.arguments(m.updateParameters, e)).toList();
        access.transaction(tx -> {
            int[] counts = tx.batchUpdate(m.update, rows);
            for (int i = 0; i < counts.length; i++)
                if (counts[i] == 0) throw new MysqlException(m.update, new SQLException(
                        "UPDATE matched no row for id " + key.getId(entities.get(i)), "02000"));
            return null;
        });
    }
    @Override public void deleteBatch(EntityMeta key, List<Object> ids) {
        var m = meta(key); var rows = ids.stream().map(id -> new Object[]{id}).toList();
        access.transaction(tx -> tx.batchUpdate(m.delete, rows));
    }
    @Override public void deleteInsertBatch(EntityMeta key, List<?> entities) {
        var m = meta(key);
        // Encode before entering the transaction so a codec failure cannot partially mutate storage.
        var inserts = entities.stream().map(e -> MysqlEntityMeta.arguments(m.fields, e)).toList();
        var deletes = entities.stream().map(e -> new Object[]{key.getId(e)}).toList();
        access.transaction(tx -> { tx.batchUpdate(m.delete, deletes); tx.batchUpdate(m.insert, inserts); return null; });
    }
}
