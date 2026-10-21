package cn.managame.data.mongo;

import cn.managame.data.annotation.*;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.key.GroupKeys;
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
        @GroupKey @Field long roleId;
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
        public void initialize(String name,List<IndexDefinition> indexes,SchemaMode schema) { assertEquals("tasks",name); this.indexes=indexes; }
        public BsonDocument findOne(String name,BsonDocument filter) { this.filter=filter; return documents.isEmpty()?null:documents.getFirst(); }
        public List<BsonDocument> find(String name,BsonDocument filter) { this.filter=filter; return documents; }
        public List<Integer> insertMany(String name,List<BsonDocument> docs) { documents=docs; operation="insert"; return List.of(); }
        long matched=-1;
        public long replaceMany(String name,List<BsonDocument> docs,boolean upsert) { documents=docs; this.upsert=upsert; operation="replace"; return matched<0?docs.size():matched; }
        public void deleteMany(String name,BsonDocument filter) { this.filter=filter; operation="delete"; }
    }
    @Test void bsonRoundTripKeepsGenericFieldsAndPrimaryIdentity() {
        var access=new Access(); var mapper=new MongoEntityMapper(access);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        var task=new Task(); task.id=1; task.roleId=2; task.taskId=3; task.progress=4; task.shortValue=12;
        task.details=List.of(new Detail("hello")); task.rewards=Map.of(7,99L); task.bytes=new byte[]{1,2};
        mapper.insertBatch(meta,List.of(task));
        var document=access.documents.getFirst();
        assertEquals(new BsonInt64(1),document.get("_id")); assertFalse(document.containsKey("ignored"));
        var restored=(Task)mapper.load(meta,1L);
        assertEquals("hello",restored.details.getFirst().text); assertEquals(Map.of(7,99L),restored.rewards);
        assertArrayEquals(task.bytes,restored.bytes); assertEquals(12,restored.shortValue);
        assertEquals(new BsonDocument("_id",new BsonInt64(1)),access.filter);
        assertEquals(1,mapper.loadGroup(meta,GroupKeys.of(2L)).size());
        assertEquals(new BsonDocument("role_id",new BsonInt64(2)),access.filter);
        assertEquals(List.of("role_id","task_id"),access.indexes.getFirst().fields());
    }
    @Test void updateDoesNotUpsertButDeleteInsertDoes() {
        var access=new Access(); var mapper=new MongoEntityMapper(access);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        var task=new Task(); task.id=7;
        mapper.updateBatch(meta,List.of(task)); assertFalse(access.upsert);
        access.matched=0; // the document is missing: the update must fail instead of disappearing
        assertThrows(cn.managame.data.error.DataOperationException.class, () -> mapper.updateBatch(meta,List.of(task)));
        access.matched=-1;
        mapper.deleteInsertBatch(meta,List.of(task)); assertTrue(access.upsert);
        assertEquals("replace",access.operation);
        mapper.deleteBatch(meta,List.of(7L,8L));
        assertEquals(new BsonArray(List.of(new BsonInt64(7),new BsonInt64(8))),access.filter.getDocument("_id").getArray("$in"));
    }

    /** A collection that keeps documents by _id, can fail an insert after storing it, and counts replacements. */
    static class Store extends Access {
        final Map<BsonValue,BsonDocument> stored=new LinkedHashMap<>();
        /** Ids an insert stores and then fails for, as a lost connection after the server applied it does. */
        final Set<Long> storeThenFail=new HashSet<>();
        int replacements;
        @Override public BsonDocument findOne(String name,BsonDocument filter) { return stored.get(filter.get("_id")); }
        @Override public List<Integer> insertMany(String name,List<BsonDocument> docs) {
            List<Integer> existing=new ArrayList<>(); boolean fail=false;
            for (int i=0;i<docs.size();i++) {
                BsonDocument doc=docs.get(i);
                if (stored.containsKey(doc.get("_id"))) { existing.add(i); continue; }
                stored.put(doc.get("_id"),doc);
                fail|=storeThenFail.remove(doc.getInt64("_id").getValue());
            }
            if (fail) throw new IllegalStateException("connection lost after the insert was applied");
            return existing;
        }
        @Override public long replaceMany(String name,List<BsonDocument> docs,boolean upsert) {
            long matched=0;
            for (BsonDocument doc : docs) {
                boolean present=stored.containsKey(doc.get("_id"));
                if (present) matched++;
                if (present||upsert) { stored.put(doc.get("_id"),doc); replacements++; }
            }
            return matched;
        }
    }
    private static Task task(long id,int progress) { var t=new Task(); t.id=id; t.roleId=1; t.taskId=id; t.progress=progress; return t; }
    private static int progress(Store store,long id) { return store.stored.get(new BsonInt64(id)).getInt32("progress").getValue(); }

    @Test void insertNeverReplacesADocumentThisMapperDidNotWrite() {
        var store=new Store(); var mapper=new MongoEntityMapper(store);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        mapper.insertBatch(meta,List.of(task(1,5)));
        var other=new MongoEntityMapper(store); other.initialize(meta,SchemaMode.CREATE);
        // D-SINGLE-02: an insert for an uncached key that storage already holds is a reported conflict.
        assertThrows(cn.managame.data.error.DataOperationException.class,()->other.insertBatch(meta,List.of(task(1,9))));
        assertEquals(5,progress(store,1)); assertEquals(0,store.replacements);
        other.insertBatch(meta,List.of(task(1,5)));   // exactly what is stored: nothing to report or write
        assertEquals(0,store.replacements);
    }
    @Test void anInsertThatFailedAfterStoringIsRecognizedOnItsRetry() {
        var store=new Store(); var mapper=new MongoEntityMapper(store);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        store.storeThenFail.add(1L);
        assertThrows(IllegalStateException.class,()->mapper.insertBatch(meta,List.of(task(1,5))));
        mapper.insertBatch(meta,List.of(task(1,6)));  // the entity changed before the retry
        assertEquals(6,progress(store,1));
        // Settled: a later foreign document under the same _id is a conflict again, not this mapper's write.
        store.stored.put(new BsonInt64(1),store.stored.get(new BsonInt64(1)).clone().append("progress",new BsonInt32(99)));
        assertThrows(cn.managame.data.error.DataOperationException.class,()->mapper.insertBatch(meta,List.of(task(1,7))));
        assertEquals(99,progress(store,1));
    }
    @Test void aConflictFailsTheBatchAndItsSplitStillSavesTheRowsTheBatchStored() {
        var store=new Store(); var mapper=new MongoEntityMapper(store);
        var meta=new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        var foreign=new MongoEntityMapper(store); foreign.initialize(meta,SchemaMode.CREATE);
        foreign.insertBatch(meta,List.of(task(2,50)));
        assertThrows(cn.managame.data.error.DataOperationException.class,()->mapper.insertBatch(meta,List.of(task(1,5),task(2,6))));
        assertEquals(5,progress(store,1)); assertEquals(50,progress(store,2));
        // The pipeline now retries each row alone; row 1 is this mapper's own even though it changed meanwhile.
        mapper.insertBatch(meta,List.of(task(1,8)));
        assertEquals(8,progress(store,1));
        assertThrows(cn.managame.data.error.DataOperationException.class,()->mapper.insertBatch(meta,List.of(task(2,6))));
        assertEquals(50,progress(store,2));
    }
}
