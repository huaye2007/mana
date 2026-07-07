package cn.managame.jpa.docdb.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.docdb.annotation.Document;
import cn.managame.jpa.docdb.annotation.Field;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.docdb.annotation.Indexed;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.metadata.DocEntityMetadataResolver;
import cn.managame.jpa.docdb.query.DocUpdateSpec;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MongoDocExecutorTest {

    @Test
    public void insertMapsIdAndDocumentFieldNames() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());
        Profile profile = new Profile(7L, "Ada", 42);

        executor.insert(metadata(), profile, null);

        assertEquals("profiles", recording.collectionName);
        assertEquals(7L, recording.inserted.get("_id"));
        assertEquals("Ada", recording.inserted.get("display_name"));
        assertEquals(42, recording.inserted.get("score"));
        assertNull(recording.createdIndex, "indexes must not be created on the hot path by default");
    }

    @Test
    public void createIndexesIsExplicit() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());

        executor.createIndexes(metadata(), null);

        assertEquals("profiles", recording.collectionName);
        assertEquals("score", recording.createdIndex.keySet().iterator().next());
    }

    @Test
    public void autoCreateIndexesCanBeOptedInForDevelopment() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database()).autoCreateIndexes(true);

        executor.insert(metadata(), new Profile(7L, "Ada", 42), null);

        assertEquals("score", recording.createdIndex.keySet().iterator().next());
    }

    @Test
    public void saveUsesPhysicalCollectionAndUpsert() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());
        Profile profile = new Profile(8L, "Grace", 51);

        executor.save(metadata(), profile, ExecutorContext.of("default", null, "profiles_01"));

        assertEquals("profiles_01", recording.collectionName);
        assertEquals(8L, recording.replacement.get("_id"));
        assertEquals("Grace", recording.replacement.get("display_name"));
        assertTrue(recording.replaceOptions.isUpsert());
    }

    @Test
    public void updateRejectsDocumentIdField() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());

        try {
            executor.update(metadata(), 7L, new DocUpdateSpec().set("id", 9L), null);
            fail("Expected id update rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("id"));
        }
    }

    @Test
    public void batchSaveUsesBulkWriteWithReplaceOneUpserts() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());
        Profile a = new Profile(10L, "Alan", 1);
        Profile b = new Profile(11L, "Bob", 2);

        executor.batchSave(metadata(), List.of(a, b), null);

        assertEquals("profiles", recording.collectionName);
        assertNotNull(recording.bulkWriteModels, "bulkWrite must be invoked");
        assertEquals(2, recording.bulkWriteModels.size());
        assertTrue(recording.bulkWriteModels.get(0) instanceof ReplaceOneModel);
        assertTrue(recording.bulkWriteModels.get(1) instanceof ReplaceOneModel);
        assertNotNull(recording.bulkWriteOptions);
        assertFalse(recording.bulkWriteOptions.isOrdered(),
                "unordered mode for parallel server-side execution");
        ReplaceOneModel<?> first = (ReplaceOneModel<?>) recording.bulkWriteModels.get(0);
        assertTrue(first.getReplaceOptions().isUpsert(), "ReplaceOne must upsert");
    }

    @Test
    public void batchDeleteUsesBulkWriteWithDeleteOne() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());

        executor.batchDelete(metadata(), List.of(20L, 21L, 22L), null);

        assertEquals(3, recording.bulkWriteModels.size());
        for (WriteModel<?> model : recording.bulkWriteModels) {
            assertTrue(model instanceof DeleteOneModel);
        }
        assertFalse(recording.bulkWriteOptions.isOrdered());
    }

    @Test
    public void idFiltersUseConvertedStorageValues() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());
        DocEntityMetadata metadata = uuidMetadata();
        UUID id = UUID.randomUUID();

        executor.save(metadata, new UuidProfile(id, "Ada"), null);
        assertEquals(id.toString(), recording.lastFilterId.asString().getValue());

        executor.findById(metadata, id, null);
        assertEquals(id.toString(), recording.lastFilterId.asString().getValue());

        executor.update(metadata, id, new DocUpdateSpec().set("name", "Grace"), null);
        assertEquals(id.toString(), recording.lastFilterId.asString().getValue());

        executor.deleteById(metadata, id, null);
        assertEquals(id.toString(), recording.lastFilterId.asString().getValue());

        executor.batchDelete(metadata, List.of(id), null);
        assertEquals(id.toString(), recording.bulkDeleteIds.get(0).asString().getValue());
    }

    @Test
    public void batchSaveEmptyDoesNotCallBulkWrite() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());

        executor.batchSave(metadata(), List.of(), null);

        assertNull(recording.bulkWriteModels);
    }

    @Test
    public void findPreservesMultipleFiltersOnSameField() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());

        executor.find(metadata(),
                new cn.managame.jpa.docdb.query.DocQuerySpec()
                        .exists("name", true)
                        .ne("name", "Ada")
                        .eq("score", 42),
                null);

        org.bson.BsonArray andFilters = recording.lastFilterDocument.getArray("$and");
        assertEquals(3, andFilters.size());
        assertTrue(andFilters.get(0).asDocument()
                .getDocument("display_name").getBoolean("$exists").getValue());
        assertEquals("Ada", andFilters.get(1).asDocument()
                .getDocument("display_name").getString("$ne").getValue());
        assertEquals(42, andFilters.get(2).asDocument().getInt32("score").getValue());
    }

    @Test
    public void pojoFieldsAreStoredAsNestedDocumentsWithoutAnnotation() {
        RecordingMongo recording = new RecordingMongo();
        MongoDocExecutor executor = new MongoDocExecutor(recording.database());
        Profile profile = new Profile(9L, "Lin", 13);
        profile.details = new Details("archer");

        executor.insert(metadata(), profile, null);

        org.bson.Document details = (org.bson.Document) recording.inserted.get("details");
        assertEquals("archer", details.get("class_name"));
    }

    private static DocEntityMetadata metadata() {
        return new DocEntityMetadataResolver().resolve(Profile.class);
    }

    private static DocEntityMetadata uuidMetadata() {
        return new DocEntityMetadataResolver().resolve(UuidProfile.class);
    }

    @Document(collection = "profiles")
    private static class Profile {
        @Id
        private long id;
        @Field(name = "display_name")
        private String name;
        @Indexed
        private int score;
        private Details details;

        Profile(long id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }
    }

    private static class Details {
        @Field(name = "class_name")
        private String className;

        Details() {
        }

        Details(String className) {
            this.className = className;
        }
    }

    @Document(collection = "uuid_profiles")
    private static class UuidProfile {
        @Id
        private UUID id;
        private String name;

        UuidProfile(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class RecordingMongo {
        private String collectionName;
        private org.bson.Document inserted;
        private org.bson.Document replacement;
        private ReplaceOptions replaceOptions;
        private org.bson.Document createdIndex;
        private BsonValue lastFilterId;
        private org.bson.BsonDocument lastFilterDocument;
        private List<BsonValue> bulkDeleteIds = List.of();
        @SuppressWarnings("rawtypes")
        private java.util.List<WriteModel> bulkWriteModels;
        private BulkWriteOptions bulkWriteOptions;

        private MongoDatabase database() {
            return (MongoDatabase) Proxy.newProxyInstance(
                    MongoDatabase.class.getClassLoader(),
                    new Class<?>[] { MongoDatabase.class },
                    (proxy, method, args) -> {
                        if ("getCollection".equals(method.getName())) {
                            collectionName = (String) args[0];
                            return collection();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private MongoCollection<org.bson.Document> collection() {
            return (MongoCollection<org.bson.Document>) Proxy.newProxyInstance(
                    MongoCollection.class.getClassLoader(),
                    new Class<?>[] { MongoCollection.class },
                    (proxy, method, args) -> {
                        if ("insertOne".equals(method.getName())) {
                            inserted = new org.bson.Document((org.bson.Document) args[0]);
                        } else if ("find".equals(method.getName())) {
                            captureFilter(args[0]);
                            return findIterable();
                        } else if ("replaceOne".equals(method.getName())) {
                            captureFilter(args[0]);
                            replacement = new org.bson.Document((org.bson.Document) args[1]);
                            replaceOptions = (ReplaceOptions) args[2];
                        } else if ("deleteOne".equals(method.getName())) {
                            captureFilter(args[0]);
                        } else if ("updateOne".equals(method.getName())) {
                            captureFilter(args[0]);
                        } else if ("createIndex".equals(method.getName())) {
                            createdIndex = new org.bson.Document((org.bson.Document) args[0]);
                        } else if ("bulkWrite".equals(method.getName())) {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            java.util.List<WriteModel> models =
                                    new java.util.ArrayList<WriteModel>((java.util.List<? extends WriteModel>) args[0]);
                            bulkWriteModels = models;
                            bulkDeleteIds = new ArrayList<>();
                            for (WriteModel<?> model : models) {
                                if (model instanceof DeleteOneModel<?> deleteOneModel) {
                                    bulkDeleteIds.add(filterId(deleteOneModel.getFilter()));
                                }
                            }
                            if (args.length > 1 && args[1] instanceof BulkWriteOptions opts) {
                                bulkWriteOptions = opts;
                            }
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private FindIterable<org.bson.Document> findIterable() {
            return (FindIterable<org.bson.Document>) Proxy.newProxyInstance(
                    FindIterable.class.getClassLoader(),
                    new Class<?>[] { FindIterable.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "first" -> null;
                        case "iterator" -> emptyCursor();
                        case "sort", "skip", "limit" -> proxy;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private com.mongodb.client.MongoCursor<org.bson.Document> emptyCursor() {
            return (com.mongodb.client.MongoCursor<org.bson.Document>) Proxy.newProxyInstance(
                    com.mongodb.client.MongoCursor.class.getClassLoader(),
                    new Class<?>[] { com.mongodb.client.MongoCursor.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasNext" -> false;
                        case "tryNext", "next" -> null;
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private void captureFilter(Object filter) {
            lastFilterDocument = ((Bson) filter)
                    .toBsonDocument(org.bson.Document.class, MongoClientSettings.getDefaultCodecRegistry());
            lastFilterId = filterId(filter);
        }

        private BsonValue filterId(Object filter) {
            return ((Bson) filter)
                    .toBsonDocument(org.bson.Document.class, MongoClientSettings.getDefaultCodecRegistry())
                    .get("_id");
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == void.class) return null;
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            return null;
        }
    }
}
