package cn.managame.data.mongo;

import java.util.List;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistry;

public interface MongoAccess {
    record IndexDefinition(String name, List<String> fields, boolean unique) {
        public IndexDefinition { fields = List.copyOf(fields); }
    }
    CodecRegistry codecRegistry();
    void initialize(String collection, List<IndexDefinition> indexes);
    BsonDocument findOne(String collection, BsonDocument filter);
    List<BsonDocument> find(String collection, BsonDocument filter);
    void insertMany(String collection, List<BsonDocument> documents);
    void replaceMany(String collection, List<BsonDocument> documents, boolean upsert);
    void deleteMany(String collection, BsonDocument filter);
}
