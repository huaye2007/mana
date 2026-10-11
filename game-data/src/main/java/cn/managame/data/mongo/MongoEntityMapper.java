package cn.managame.data.mongo;

import cn.managame.data.key.GroupKey;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import com.mongodb.client.MongoDatabase;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.*;

public final class MongoEntityMapper implements EntityMapper {
    private final MongoAccess access;
    private final Map<EntityMeta,MongoEntityMeta> metas = new ConcurrentHashMap<>();
    public MongoEntityMapper(MongoDatabase database) { this(new DriverMongoAccess(database)); }
    public MongoEntityMapper(MongoAccess access) { this.access = Objects.requireNonNull(access); }
    @Override public void initialize(EntityMeta key) {
        metas.computeIfAbsent(key, entity -> {
            var result = new MongoEntityMeta(entity, access.codecRegistry());
            access.initialize(result.collection, result.indexes); return result;
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
    @Override public List<?> loadGroup(EntityMeta key, GroupKey group) {
        key.validateGroupKey(group); var m = meta(key); var filter = new BsonDocument();
        for (int i = 0; i < group.size(); i++)
            filter.append(m.groups.get(i).name(), m.groups.get(i).codec().encode(group.valueAt(i)));
        return access.find(m.collection, filter).stream().map(m::decode).toList();
    }
    /** Upsert by _id: a batch retried after partial success cannot fail on duplicate keys. */
    @Override public void insertBatch(EntityMeta key, List<?> entities) { replace(key, entities, true); }
    @Override public void updateBatch(EntityMeta key, List<?> entities) { replace(key, entities, false); }
    @Override public void deleteInsertBatch(EntityMeta key, List<?> entities) { replace(key, entities, true); }
    private void replace(EntityMeta key, List<?> entities, boolean upsert) {
        var m = meta(key); access.replaceMany(m.collection, entities.stream().map(m::encode).toList(), upsert);
    }
    @Override public void deleteBatch(EntityMeta key, List<Object> ids) {
        var m = meta(key);
        access.deleteMany(m.collection, new BsonDocument("_id",
                new BsonDocument("$in", new BsonArray(ids.stream().map(m.id.codec()::encode).toList()))));
    }
}
