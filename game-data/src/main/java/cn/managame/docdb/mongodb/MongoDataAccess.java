package cn.managame.docdb.mongodb;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.access.Query;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.IndexMetadata;
import cn.managame.core.metadata.PropertyMetadata;
import cn.managame.core.write.BatchItemResult;
import cn.managame.core.write.BatchItemState;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.BatchWriteException;
import cn.managame.core.write.WriteOperation;
import cn.managame.core.write.WriteType;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import cn.managame.annotation.CompoundIndex;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.*;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.*;

/** MongoDB implementation inside the document-database module. */
public final class MongoDataAccess implements DataAccess {
    private final MongoClient client;
    private final MongoDatabase database;
    private final EntityMapper mapper;
    private final boolean ownsClient;
    private final int maxBatchSize;
    private final int scanBatchSize;

    public MongoDataAccess(String connectionString, String databaseName) {
        this(MongoClients.create(connectionString), databaseName, new EntityMapper(new MongoValueConverter()), true, 2_000, 512);
    }

    public MongoDataAccess(MongoClient client, String databaseName) {
        this(client, databaseName, new EntityMapper(new MongoValueConverter()), false, 2_000, 512);
    }

    public MongoDataAccess(MongoClient client, String databaseName, EntityMapper mapper, boolean ownsClient) {
        this(client, databaseName, mapper, ownsClient, 2_000, 512);
    }

    public MongoDataAccess(
            MongoClient client,
            String databaseName,
            EntityMapper mapper,
            boolean ownsClient,
            int maxBatchSize,
            int scanBatchSize) {
        this.client = Objects.requireNonNull(client);
        this.database = client.getDatabase(Objects.requireNonNull(databaseName));
        this.mapper = Objects.requireNonNull(mapper);
        this.ownsClient = ownsClient;
        if (maxBatchSize <= 0) throw new IllegalArgumentException("maxBatchSize must be > 0");
        if (scanBatchSize <= 0) throw new IllegalArgumentException("scanBatchSize must be > 0");
        this.maxBatchSize = maxBatchSize;
        this.scanBatchSize = scanBatchSize;
    }

    @Override public void validateMapping(EntityMetadata<?> metadata) { mapper.validate(metadata); }

    @Override
    public String writerKey(EntityMetadata<?> metadata, String physicalName) {
        String collection = physicalName == null || physicalName.isBlank() ? metadata.docCollection() : physicalName;
        return "docdb:" + database.getName() + "." + collection;
    }

    @Override
    public <T, ID> Optional<T> findById(EntityMetadata<T> metadata, ID id) {
        Object storeId = mapper.toStore(id, metadata.idProperty().type());
        Document doc = collection(metadata, null).find(eq("_id", storeId)).first();
        return doc == null ? Optional.empty() : Optional.of(fromDocument(metadata, doc));
    }

    @Override
    public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
        FindIterable<Document> find = collection(metadata, null).find(toFilter(metadata, query));
        if (query.limit() > 0) find = find.limit(query.limit());
        List<T> result = new ArrayList<>();
        for (Document d : find) result.add(fromDocument(metadata, d));
        return result;
    }

    @Override
    public <T> void scan(EntityMetadata<T> metadata, Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        try (MongoCursor<Document> cursor = collection(metadata, null).find().batchSize(scanBatchSize).iterator()) {
            while (cursor.hasNext()) consumer.accept(fromDocument(metadata, cursor.next()));
        }
    }

    @Override
    public BatchResult applyBatch(List<WriteOperation<?>> operations) {
        if (operations.isEmpty()) return BatchResult.success(0);
        EntityMetadata<?> metadata = operations.getFirst().metadata();
        String physicalName = operations.getFirst().physicalName();
        for (WriteOperation<?> operation : operations) {
            if (operation.metadata().type() != metadata.type()) {
                throw new DataException("MongoDB batch must contain one collection/entity type only");
            }
            if (!Objects.equals(operation.physicalName(), physicalName)) {
                throw new DataException("MongoDB batch must contain one physical collection only");
            }
        }
        return applyEntityBatch(metadata, physicalName, operations);
    }

    private BatchResult applyEntityBatch(EntityMetadata<?> metadata, String physicalName, List<WriteOperation<?>> operations) {
        List<WriteModel<Document>> models = new ArrayList<>(operations.size());
        int expectedUpdates = 0;
        int expectedSingleDeletes = 0;

        for (WriteOperation<?> op : operations) {
            switch (op.type()) {
                case INSERT -> models.add(new InsertOneModel<>(toDocument(metadata, op.entity())));
                case UPDATE -> {
                    Object storeId = mapper.toStore(op.id(), metadata.idProperty().type());
                    Document set = toDocument(metadata, op.entity());
                    set.remove("_id");
                    models.add(new UpdateOneModel<>(eq("_id", storeId), new Document("$set", set)));
                    expectedUpdates++;
                }
                case DELETE -> {
                    Object storeId = mapper.toStore(op.id(), metadata.idProperty().type());
                    models.add(new DeleteOneModel<>(eq("_id", storeId)));
                    expectedSingleDeletes++;
                }
                case DELETE_GROUP -> models.add(new DeleteManyModel<>(groupFilter(metadata, op.groupKey())));
            }
        }

        if (models.isEmpty()) return BatchResult.success(operations.size());
        try {
            var result = collection(metadata, physicalName).bulkWrite(models, new BulkWriteOptions().ordered(true));
            List<BatchItemResult> problems = new ArrayList<>();
            Set<Integer> problemIndexes = new HashSet<>();
            if (result.getMatchedCount() < expectedUpdates) {
                String message = "MongoDB update matched fewer documents than expected: expected="
                        + expectedUpdates + ", matched=" + result.getMatchedCount();
                for (int i = 0; i < operations.size(); i++) {
                    if (operations.get(i).type() == WriteType.UPDATE) {
                        problems.add(new BatchItemResult(i, BatchItemState.UNKNOWN, message));
                        problemIndexes.add(i);
                    }
                }
            }
            if (result.getDeletedCount() < expectedSingleDeletes) {
                String message = "MongoDB delete matched fewer documents than expected: expectedAtLeast="
                        + expectedSingleDeletes + ", deleted=" + result.getDeletedCount();
                for (int i = 0; i < operations.size(); i++) {
                    if (operations.get(i).type() == WriteType.DELETE && !problemIndexes.contains(i)) {
                        problems.add(new BatchItemResult(i, BatchItemState.UNKNOWN, message));
                        problemIndexes.add(i);
                    }
                }
            }
            return problems.isEmpty() ? BatchResult.success(operations.size())
                    : BatchResult.of(operations.size(), problems);
        } catch (MongoBulkWriteException e) {
            throw new BatchWriteException("MongoDB ordered bulk write failed for "
                    + metadata.type().getName(), orderedBulkFailure(operations, e), e);
        } catch (MongoException e) {
            throw new BatchWriteException("MongoDB bulk write result is unknown for "
                    + metadata.type().getName(), BatchResult.unknown(operations.size(), e.getMessage()), e);
        }
    }

    private BatchResult orderedBulkFailure(List<WriteOperation<?>> operations, MongoBulkWriteException error) {
        int operationCount = operations.size();
        List<BulkWriteError> writeErrors = error.getWriteErrors();
        List<BatchItemResult> problems = new ArrayList<>();
        boolean writeConcernUnknown = error.getWriteConcernError() != null;

        int firstError = writeErrors.stream().mapToInt(BulkWriteError::getIndex).min().orElse(-1);
        int expectedUpdates = 0;
        for (int i = 0; i < firstError; i++) {
            if (operations.get(i).type() == WriteType.UPDATE) expectedUpdates++;
        }
        // Counts describe the executed prefix only; aggregate counts cannot identify individual misses.
        var written = error.getWriteResult();
        boolean updatesUnknown = expectedUpdates > 0
                && (!written.wasAcknowledged() || written.getMatchedCount() < expectedUpdates);
        Set<Integer> failed = new HashSet<>();
        for (BulkWriteError writeError : writeErrors) {
            int index = writeError.getIndex();
            failed.add(index);
            problems.add(new BatchItemResult(index, BatchItemState.FAILED,
                    "MongoDB write error code=" + writeError.getCode() + ": " + writeError.getMessage()));
        }

        for (int i = 0; i < operationCount; i++) {
            if (failed.contains(i)) continue;
            if (firstError >= 0 && i > firstError) {
                problems.add(new BatchItemResult(i, BatchItemState.UNEXECUTED,
                        "MongoDB ordered bulk stopped after operation " + firstError));
            } else if (writeConcernUnknown) {
                problems.add(new BatchItemResult(i, BatchItemState.UNKNOWN,
                        "MongoDB write concern failed; final persistence state is unknown"));
            } else if (updatesUnknown && operations.get(i).type() == WriteType.UPDATE) {
                problems.add(new BatchItemResult(i, BatchItemState.UNKNOWN,
                        "MongoDB executed-prefix update matches are incomplete; individual results are unknown"));
            }
        }

        if (problems.isEmpty()) {
            return BatchResult.unknown(operationCount, "MongoDB bulk write failed without an indexed write error");
        }
        return BatchResult.of(operationCount, problems);
    }

    @Override
    public int maxBatchSize() { return maxBatchSize; }

    private Bson groupFilter(EntityMetadata<?> metadata, Object groupKey) {
        List<Object> values = metadata.groupKeyValues(groupKey);
        List<PropertyMetadata> properties = metadata.groupKeyProperties();
        if (properties.isEmpty()) throw new DataException("DELETE_GROUP requires @GroupKey: " + metadata.type().getName());
        List<Bson> filters = new ArrayList<>(properties.size());
        for (int i = 0; i < properties.size(); i++) {
            PropertyMetadata property = properties.get(i);
            filters.add(eq(property.docName(), mapper.toStore(values.get(i), property)));
        }
        return filters.size() == 1 ? filters.getFirst() : and(filters);
    }

    private <T> T fromDocument(EntityMetadata<T> metadata, Document document) {
        return mapper.fromDocument(metadata, document);
    }

    private Document toDocument(EntityMetadata<?> metadata, Object entity) {
        Document document = new Document();
        for (PropertyMetadata property : metadata.properties()) {
            document.put(property.docName(), mapper.toStore(property.get(entity), property));
        }
        return document;
    }

    @Override
    public void ensureSchema(EntityMetadata<?> metadata) { ensureSchema(metadata, null); }

    @Override
    public void ensureSchema(EntityMetadata<?> metadata, String physicalName) {
        String collectionName = physicalName == null || physicalName.isBlank()
                ? metadata.docCollection() : physicalName;
        Set<String> collections = new HashSet<>();
        for (String name : database.listCollectionNames()) collections.add(name);
        if (!collections.contains(collectionName)) {
            try {
                database.createCollection(collectionName);
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != 48) throw e;
            }
        }

        MongoCollection<Document> collection = database.getCollection(collectionName);
        for (IndexMetadata idx : metadata.indexes()) {
            Document key = new Document();
            for (IndexMetadata.Column column : idx.columns()) {
                String field = metadata.property(column.fieldName()).docName();
                key.append(field, column.direction() == cn.managame.annotation.IndexDirection.DESC ? -1 : 1);
            }
            collection.createIndex(key, new IndexOptions()
                    .name(idx.name()).unique(idx.unique()).sparse(idx.sparse()));
        }

        for (CompoundIndex idx : metadata.compoundIndexes()) {
            Document keys = Document.parse(idx.def());
            IndexOptions options = new IndexOptions().unique(idx.unique());
            if (!idx.name().isBlank()) options.name(idx.name());
            collection.createIndex(keys, options);
        }
    }

    private Bson toFilter(EntityMetadata<?> metadata, Query query) {
        if (query.criteria().isEmpty()) return new Document();
        List<Bson> filters = new ArrayList<>();
        for (Query.Criterion criterion : query.criteria()) {
            PropertyMetadata property = metadata.property(criterion.property());
            String field = property.docName();
            Object value = mapper.toStore(criterion.value(), property);
            filters.add(switch (criterion.operator()) {
                case EQ -> eq(field, value);
                case NE -> ne(field, value);
                case GT -> gt(field, value);
                case GTE -> gte(field, value);
                case LT -> lt(field, value);
                case LTE -> lte(field, value);
                case IN -> {
                    if (!(criterion.value() instanceof Collection<?> values)) {
                        throw new DataException("IN requires Collection: " + criterion.property());
                    }
                    List<Object> stored = new ArrayList<>(values.size());
                    for (Object item : values) stored.add(mapper.toStore(item, property));
                    yield in(field, stored);
                }
            });
        }
        return filters.size() == 1 ? filters.getFirst() : and(filters);
    }

    @Override
    public String defaultPhysicalName(EntityMetadata<?> metadata) { return metadata.docCollection(); }

    private MongoCollection<Document> collection(EntityMetadata<?> metadata, String physicalName) {
        String name = physicalName == null || physicalName.isBlank() ? metadata.docCollection() : physicalName;
        return database.getCollection(name);
    }

    @Override
    public void close() { if (ownsClient) client.close(); }
}
