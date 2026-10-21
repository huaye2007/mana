package cn.managame.data.mongo;

import cn.managame.data.error.DataOperationException;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import com.mongodb.client.MongoDatabase;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.*;

public final class MongoEntityMapper implements EntityMapper {
    /**
     * What a failed insert sent for one {@code _id}. Only one insert of an {@code _id} can ever be stored, and
     * the first failed attempt is the likeliest to have reached the database before the failure (later ones
     * usually fail before sending anything), so the first and the latest are kept rather than every attempt.
     */
    private record Unsettled(BsonDocument first, BsonDocument latest) {
        boolean wrote(BsonDocument stored) { return stored != null && (stored.equals(first) || stored.equals(latest)); }
    }

    private final MongoAccess access;
    private final Map<EntityMeta,MongoEntityMeta> metas = new ConcurrentHashMap<>();
    /**
     * By collection, then {@code _id}: inserts whose outcome is unknown because their call failed. An entry
     * lives from that failure until a save of its {@code _id} settles it. Only the persistence pipeline calls a
     * mapper, so entries exist only while a failed change is waiting for its retry or split.
     */
    private final Map<String, Map<BsonValue, Unsettled>> unsettled = new ConcurrentHashMap<>();
    public MongoEntityMapper(MongoDatabase database) { this(new DriverMongoAccess(database)); }
    public MongoEntityMapper(MongoAccess access) { this.access = Objects.requireNonNull(access); }
    @Override public void initialize(EntityMeta key, SchemaMode schema) {
        Objects.requireNonNull(schema, "schema");
        metas.computeIfAbsent(key, entity -> {
            var result = new MongoEntityMeta(entity, access.codecRegistry());
            // NONE touches no catalog at all, so the Access is never asked about structure.
            if (schema != SchemaMode.NONE) access.initialize(result.collection, result.indexes, schema);
            return result;
        });
    }
    private MongoEntityMeta meta(EntityMeta key) {
        var result = metas.get(key);
        if (result == null) throw new IllegalStateException("Mapper not initialized");
        return result;
    }
    @Override public Object load(EntityMeta key, Object id) {
        var m = meta(key);
        return m.decode(access.findOne(m.collection, new BsonDocument("_id", m.id.codec().encode(id))));
    }
    @Override public List<?> loadGroup(EntityMeta key, GroupKeys group) {
        key.validateGroupKey(group); var m = meta(key); var filter = new BsonDocument();
        for (int i = 0; i < group.size(); i++)
            filter.append(m.groups.get(i).name(), m.groups.get(i).codec().encode(group.valueAt(i)));
        return access.find(m.collection, filter).stream().map(m::decode).toList();
    }
    /**
     * Inserts without ever replacing a stored document (D-SINGLE-02). An {@code _id} that is already stored is
     * this mapper's own write when the stored document is what it sent — now, or in an insert of that
     * {@code _id} that failed without saying what it stored — and is then brought up to the current state.
     * Any other stored document is a storage conflict: the batch throws and that document is left as it is.
     *
     * <p>A multi-document insert is not atomic, so a batch that throws may have stored some of its documents,
     * and the retry or the split into single rows that follows meets them as existing {@code _id}s. Recognizing
     * them is what keeps that retry from reporting this mapper's own rows as conflicts.
     */
    @Override public void insertBatch(EntityMeta key, List<?> entities) {
        var m = meta(key);
        List<BsonDocument> documents = entities.stream().map(m::encode).toList();
        var sent = unsettled.computeIfAbsent(m.collection, ignored -> new ConcurrentHashMap<>());
        Set<BsonValue> conflicts = new LinkedHashSet<>();
        try {
            for (int index : access.insertMany(m.collection, documents)) {
                BsonDocument document = documents.get(index);
                BsonValue id = document.get("_id");
                BsonDocument stored = access.findOne(m.collection, new BsonDocument("_id", id));
                if (document.equals(stored)) continue;        // already exactly what this insert writes
                Unsettled earlier = sent.get(id);
                if (earlier != null && earlier.wrote(stored)) access.replaceMany(m.collection, List.of(document), true);
                else conflicts.add(id);
            }
        } catch (RuntimeException | Error failure) {
            remember(sent, documents, conflicts);
            throw failure;
        }
        if (!conflicts.isEmpty()) {
            // The batch is reported failed and split into single rows, which meet the rows it did store.
            remember(sent, documents, conflicts);
            throw new DataOperationException("INSERT found " + conflicts.size() + " existing document(s) in "
                    + m.collection + " that this mapper did not write, _id " + conflicts);
        }
        for (BsonDocument document : documents) sent.remove(document.get("_id"));
    }
    @Override public void updateBatch(EntityMeta key, List<?> entities) { replace(key, entities, false); }
    @Override public void deleteInsertBatch(EntityMeta key, List<?> entities) { replace(key, entities, true); }
    /** An update whose document is missing fails, like MySQL: silently succeeding would lose the change. */
    private void replace(EntityMeta key, List<?> entities, boolean upsert) {
        var m = meta(key);
        List<BsonDocument> documents = entities.stream().map(m::encode).toList();
        long matched = access.replaceMany(m.collection, documents, upsert);
        if (!upsert && matched != entities.size())
            throw new DataOperationException("UPDATE matched " + matched + " of " + entities.size()
                    + " documents in " + m.collection);
        settle(m.collection, documents.stream().map(document -> document.get("_id")).toList());
    }
    @Override public void deleteBatch(EntityMeta key, List<Object> ids) {
        var m = meta(key);
        List<BsonValue> encoded = ids.stream().map(m.id.codec()::encode).toList();
        access.deleteMany(m.collection, new BsonDocument("_id", new BsonDocument("$in", new BsonArray(encoded))));
        settle(m.collection, encoded);
    }

    /** A conflict is somebody else's document, so it is never remembered as this mapper's. */
    private static void remember(Map<BsonValue, Unsettled> sent, List<BsonDocument> documents, Set<BsonValue> conflicts) {
        for (BsonDocument document : documents) {
            BsonValue id = document.get("_id");
            if (conflicts.contains(id)) sent.remove(id);
            else sent.merge(id, new Unsettled(document, document), (earlier, now) -> new Unsettled(earlier.first(), document));
        }
    }
    private void settle(String collection, List<BsonValue> ids) {
        var sent = unsettled.get(collection);
        if (sent != null) for (BsonValue id : ids) sent.remove(id);
    }
}
