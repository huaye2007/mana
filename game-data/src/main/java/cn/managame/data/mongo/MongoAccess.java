package cn.managame.data.mongo;

import cn.managame.data.mapper.SchemaMode;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistry;

public interface MongoAccess {
    record IndexDefinition(String name, List<String> fields, boolean unique) {
        public IndexDefinition { fields = List.copyOf(fields); }
    }
    CodecRegistry codecRegistry();
    /**
     * Brings the collection and its declared indexes in line with the mapping, as far as {@code schema}
     * allows: CREATE creates what is missing, VALIDATE reports it instead. Never called with
     * {@link SchemaMode#NONE}, which reads no catalog at all.
     */
    void initialize(String collection, List<IndexDefinition> indexes, SchemaMode schema);
    BsonDocument findOne(String collection, BsonDocument filter);
    List<BsonDocument> find(String collection, BsonDocument filter);
    /**
     * Inserts every document whose {@code _id} is not stored yet, never replacing a stored one, and returns
     * the positions, ascending, of the documents refused only because their {@code _id} already exists. Any
     * other failure throws, and the documents it did not concern may be stored anyway: an insert of several
     * documents is not atomic.
     */
    List<Integer> insertMany(String collection, List<BsonDocument> documents);
    /** Replaces by _id in order and returns how many documents already existed (matched). */
    long replaceMany(String collection, List<BsonDocument> documents, boolean upsert);
    void deleteMany(String collection, BsonDocument filter);
}
