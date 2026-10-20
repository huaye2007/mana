package cn.managame.data.mongo;

import cn.managame.data.mapper.SchemaMode;
import com.mongodb.MongoCommandException;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import java.util.*;
import org.bson.*;
import org.bson.codecs.configuration.CodecRegistry;

/** Does not own or close the supplied MongoDatabase/MongoClient. */
public final class DriverMongoAccess implements MongoAccess {
    private final MongoDatabase database;
    public DriverMongoAccess(MongoDatabase database) { this.database = Objects.requireNonNull(database); }
    @Override public CodecRegistry codecRegistry() { return database.getCodecRegistry(); }
    private MongoCollection<BsonDocument> collection(String name) { return database.getCollection(name, BsonDocument.class); }
    /** CREATE creates a missing collection or index, tolerating another instance that got there first. */
    @Override public void initialize(String name, List<IndexDefinition> indexes, SchemaMode schema) {
        boolean create = schema == SchemaMode.CREATE;
        if (!database.listCollectionNames().into(new ArrayList<>()).contains(name)) {
            if (!create) throw new IllegalArgumentException("Schema conflict on " + name
                    + ": collection does not exist and schema is " + schema);
            // 48 NamespaceExists: another instance created it between the check and this command.
            try { database.createCollection(name); }
            catch (MongoCommandException e) { if (e.getErrorCode() != 48) throw e; }
        }
        var collection = collection(name);
        var actual = collection.listIndexes(BsonDocument.class).into(new ArrayList<>());
        for (IndexDefinition index : indexes) {
            BsonDocument keys = new BsonDocument();
            index.fields().forEach(f -> keys.append(f, new BsonInt32(1)));
            BsonDocument existing = actual.stream().filter(d -> d.getString("name").getValue().equals(index.name())).findFirst().orElse(null);
            if (existing == null) {
                if (!create) throw new IllegalArgumentException("Schema conflict on " + name
                        + ": index " + index.name() + " is missing and schema is " + schema);
                // An identical createIndex is accepted again, so a concurrent one needs no special case.
                collection.createIndex(keys, new IndexOptions().name(index.name()).unique(index.unique()));
            } else {
                BsonDocument present = existing.getDocument("key");
                if (!new ArrayList<>(present.entrySet()).equals(new ArrayList<>(keys.entrySet()))
                        || existing.getBoolean("unique", BsonBoolean.FALSE).getValue() != index.unique())
                    throw new IllegalArgumentException("Mongo index conflict: " + index.name());
            }
        }
    }
    @Override public BsonDocument findOne(String name, BsonDocument filter) { return collection(name).find(filter).first(); }
    @Override public List<BsonDocument> find(String name, BsonDocument filter) { return collection(name).find(filter).into(new ArrayList<>()); }
    @Override public void insertMany(String name, List<BsonDocument> documents) {
        if (!documents.isEmpty()) collection(name).insertMany(documents);
    }
    @Override public long replaceMany(String name, List<BsonDocument> documents, boolean upsert) {
        if (documents.isEmpty()) return 0;
        List<WriteModel<BsonDocument>> changes = new ArrayList<>();
        for (BsonDocument document : documents)
            changes.add(new ReplaceOneModel<>(new BsonDocument("_id", document.get("_id")), document, new ReplaceOptions().upsert(upsert)));
        return collection(name).bulkWrite(changes, new BulkWriteOptions().ordered(true)).getMatchedCount();
    }
    @Override public void deleteMany(String name, BsonDocument filter) { collection(name).deleteMany(filter); }
}
