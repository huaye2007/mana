package cn.managame.data.mongo;

import cn.managame.data.annotation.*;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.key.GroupKey;
import com.mongodb.MongoClientSettings;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.codecs.configuration.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MongoMappingTest {
    static class Detail { String text; Detail(String text) { this.text=text; } }
    @Collection(name="tasks",indexes=@MongoIndex(fields={"role_id","task_id"}))
    static class Task {
        @Id @Field long id;
        @cn.managame.data.annotation.GroupKey @Field long roleId;
        @MapKey @Field long taskId;
        @Field int progress;
        @Field List<Detail> details;
        @Field Map<Integer,Long> rewards;
        @Field byte[] bytes;
        @Field short shortValue;
        String ignored;
    }
    static class Access implements MongoAccess {
        BsonDocument filter; List<BsonDocument> documents=List.of(); boolean upsert; String operation;
        List<IndexDefinition> indexes;
        public CodecRegistry codecRegistry() {
            Codec<Detail> codec=new Codec<>() {
                public Class<Detail> getEncoderClass() { return Detail.class; }
                public void encode(BsonWriter w,Detail v,EncoderContext c) {
                    w.writeStartDocument(); w.writeString("text",v.text); w.writeEndDocument();
                }
                public Detail decode(BsonReader r,DecoderContext c) {
                    r.readStartDocument(); String text=r.readString("text"); r.readEndDocument(); return new Detail(text);
                }
            };
            return CodecRegistries.fromRegistries(CodecRegistries.fromCodecs(codec),MongoClientSettings.getDefaultCodecRegistry());
        }
        public void initialize(String name,List<IndexDefinition> indexes) { assertEquals("tasks",name); this.indexes=indexes; }
        public BsonDocument findOne(String name,BsonDocument filter) { this.filter=filter; return documents.isEmpty()?null:documents.getFirst(); }
        public List<BsonDocument> find(String name,BsonDocument filter) { this.filter=filter; return documents; }
        public void insertMany(String name,List<BsonDocument> docs) { documents=docs; operation="insert"; }
        public void replaceMany(String name,List<BsonDocument> docs,boolean upsert) { documents=docs; this.upsert=upsert; operation="replace"; }
        public void deleteMany(String name,BsonDocument filter) { this.filter=filter; operation="delete"; }
    }
    @Test void bsonRoundTripKeepsGenericFieldsAndPrimaryIdentity() {
        var access=new Access(); var mapper=new MongoEntityMapper(access);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta);
        var task=new Task(); task.id=1; task.roleId=2; task.taskId=3; task.progress=4; task.shortValue=12;
        task.details=List.of(new Detail("hello")); task.rewards=Map.of(7,99L); task.bytes=new byte[]{1,2};
        mapper.insertBatch(meta,List.of(task));
        var document=access.documents.getFirst();
        assertEquals(new BsonInt64(1),document.get("_id")); assertFalse(document.containsKey("ignored"));
        var restored=(Task)mapper.load(meta,1L);
        assertEquals("hello",restored.details.getFirst().text); assertEquals(Map.of(7,99L),restored.rewards);
        assertArrayEquals(task.bytes,restored.bytes); assertEquals(12,restored.shortValue);
        assertEquals(new BsonDocument("_id",new BsonInt64(1)),access.filter);
        assertEquals(1,mapper.loadGroup(meta,GroupKey.of(2L)).size());
        assertEquals(new BsonDocument("role_id",new BsonInt64(2)),access.filter);
        assertEquals(List.of("role_id","task_id"),access.indexes.getFirst().fields());
    }
    @Test void updateDoesNotUpsertButDeleteInsertDoes() {
        var access=new Access(); var mapper=new MongoEntityMapper(access);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta);
        var task=new Task(); task.id=7;
        mapper.updateBatch(meta,List.of(task)); assertFalse(access.upsert);
        mapper.deleteInsertBatch(meta,List.of(task)); assertTrue(access.upsert);
        assertEquals("replace",access.operation);
        mapper.deleteBatch(meta,List.of(7L,8L));
        assertEquals(new BsonArray(List.of(new BsonInt64(7),new BsonInt64(8))),access.filter.getDocument("_id").getArray("$in"));
    }
}
