package cn.managame.jpa.docdb.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DuplicateKeyException;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.converter.TypeConverterAware;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.metadata.ReflectionUtils;
import cn.managame.jpa.core.registry.DataSourceCatalog;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.metadata.DocFieldMetadata;
import cn.managame.jpa.docdb.metadata.DocTypes;
import cn.managame.jpa.docdb.query.DocQuerySpec;
import cn.managame.jpa.docdb.query.DocUpdateSpec;
import org.bson.Document;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MongoDocExecutor implements DocExecutor, Closeable, TypeConverterAware, DataSourceCatalog {

    private final DataSourceRegistry<MongoDatabase> databaseRegistry;
    private final List<MongoClient> managedClients = new ArrayList<>();
    private final Set<String> ensuredIndexes = ConcurrentHashMap.newKeySet();
    /** Embedded-field metadata is immutable; cache it per Field to avoid rebuilding a
     * FieldAccessor on every document mapped during high-throughput batch writes. */
    private final Map<Field, DocFieldMetadata> embeddedFieldCache = new ConcurrentHashMap<>();
    private volatile boolean autoCreateIndexes;
    private volatile TypeConverterRegistry converterRegistry = new TypeConverterRegistry();

    @Override
    public Set<String> dataSourceNames() {
        return databaseRegistry.names();
    }

    public MongoDocExecutor(String connectionString, String databaseName) {
        MongoClient client = MongoClients.create(connectionString);
        this.databaseRegistry = new DataSourceRegistry<>();
        this.databaseRegistry.registerDefault(client.getDatabase(databaseName));
        this.managedClients.add(client);
    }

    public MongoDocExecutor(MongoClient client, String databaseName) {
        this.databaseRegistry = new DataSourceRegistry<>();
        this.databaseRegistry.registerDefault(client.getDatabase(databaseName));
    }

    public MongoDocExecutor(MongoDatabase database) {
        this.databaseRegistry = new DataSourceRegistry<>();
        this.databaseRegistry.registerDefault(database);
    }

    public MongoDocExecutor(DataSourceRegistry<MongoDatabase> databaseRegistry) {
        this.databaseRegistry = databaseRegistry;
    }

    public MongoDocExecutor autoCreateIndexes(boolean enabled) {
        this.autoCreateIndexes = enabled;
        return this;
    }

    @Override
    public void setTypeConverterRegistry(TypeConverterRegistry registry) {
        this.converterRegistry = registry != null ? registry : new TypeConverterRegistry();
    }

    public void createIndexes(DocEntityMetadata metadata, ExecutorContext context) {
        String collectionName = resolveCollectionName(metadata, context);
        MongoCollection<Document> collection = database(context).getCollection(collectionName);
        ensureIndexes(metadata, context, collectionName, collection);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
        try {
            Document document = collection(metadata, context)
                    .find(Filters.eq("_id", storageId(metadata, id)))
                    .first();
            return document != null ? (T) mapFromDocument(metadata, document) : null;
        } catch (MongoException e) {
            throw wrapMongoException("findById", metadata, context, id, e);
        }
    }

    @Override
    public void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
        try {
            collection(metadata, context).insertOne(new Document(mapToDocument(metadata, entity)));
        } catch (MongoException e) {
            throw wrapMongoException("insert", metadata, context,
                    metadata.idField().accessor().get(entity), e);
        }
    }

    @Override
    public void save(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
        Object id = storageId(metadata, metadata.idField().accessor().get(entity));
        try {
            collection(metadata, context).replaceOne(
                    Filters.eq("_id", id),
                    new Document(mapToDocument(metadata, entity)),
                    new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw wrapMongoException("save", metadata, context, id, e);
        }
    }

    @Override
    public void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
        try {
            collection(metadata, context).deleteOne(Filters.eq("_id", storageId(metadata, id)));
        } catch (MongoException e) {
            throw wrapMongoException("deleteById", metadata, context, id, e);
        }
    }

    /**
     * 用 Mongo bulkWrite 一次网络往返完成多条 upsert。
     * unordered 模式：允许部分失败继续执行后续，单次失败不阻塞整批。
     */
    @Override
    public void batchSave(DocEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        if (entities.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> models = new ArrayList<>(entities.size());
        ReplaceOptions options = new ReplaceOptions().upsert(true);
        for (Object entity : entities) {
            Object id = storageId(metadata, metadata.idField().accessor().get(entity));
            models.add(new ReplaceOneModel<>(
                    Filters.eq("_id", id),
                    new Document(mapToDocument(metadata, entity)),
                    options));
        }
        try {
            collection(metadata, context).bulkWrite(models, new BulkWriteOptions().ordered(false));
        } catch (MongoException e) {
            throw wrapMongoException("batchSave", metadata, context, null, e);
        }
    }

    /**
     * 用 Mongo bulkWrite 一次网络往返完成多条 deleteOne。
     */
    @Override
    public void batchDelete(DocEntityMetadata metadata, List<?> ids, ExecutorContext context) {
        if (ids.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> models = new ArrayList<>(ids.size());
        for (Object id : ids) {
            models.add(new DeleteOneModel<>(Filters.eq("_id", storageId(metadata, id))));
        }
        try {
            collection(metadata, context).bulkWrite(models, new BulkWriteOptions().ordered(false));
        } catch (MongoException e) {
            throw wrapMongoException("batchDelete", metadata, context, null, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context) {
        try {
            List<Object> result = new ArrayList<>();
            for (Document document : collection(metadata, context).find()) {
                result.add(mapFromDocument(metadata, document));
            }
            return (List<T>) result;
        } catch (MongoException e) {
            throw wrapMongoException("findAll", metadata, context, null, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec, ExecutorContext context) {
        try {
            var iterable = collection(metadata, context).find(new Document(buildFilter(metadata, querySpec)));
            Map<String, Integer> sort = buildSort(metadata, querySpec);
            if (!sort.isEmpty()) {
                iterable = iterable.sort(new Document(sort));
            }
            if (querySpec.skip() > 0) {
                iterable = iterable.skip(querySpec.skip());
            }
            if (querySpec.limit() > 0) {
                iterable = iterable.limit(querySpec.limit());
            }

            List<Object> result = new ArrayList<>();
            for (Document document : iterable) {
                result.add(mapFromDocument(metadata, document));
            }
            return (List<T>) result;
        } catch (MongoException e) {
            throw wrapMongoException("find", metadata, context, null, e);
        }
    }

    @Override
    public void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec, ExecutorContext context) {
        Document update = new Document(buildUpdate(metadata, updateSpec));
        try {
            collection(metadata, context).updateOne(
                    Filters.eq("_id", storageId(metadata, id)), update);
        } catch (MongoException e) {
            throw wrapMongoException("update", metadata, context, id, e);
        }
    }

    @Override
    public void close() {
        for (MongoClient client : managedClients) {
            client.close();
        }
    }

    private MongoCollection<Document> collection(DocEntityMetadata metadata, ExecutorContext context) {
        String collectionName = resolveCollectionName(metadata, context);
        MongoCollection<Document> collection = database(context).getCollection(collectionName);
        if (autoCreateIndexes) {
            ensureIndexes(metadata, context, collectionName, collection);
        }
        return collection;
    }

    private MongoDatabase database(ExecutorContext context) {
        ExecutorContext actualContext = context != null ? context : ExecutorContext.defaultContext();
        return databaseRegistry.get(actualContext.dataSourceName());
    }

    private String resolveCollectionName(DocEntityMetadata metadata, ExecutorContext context) {
        if (context != null && context.physicalTableName() != null) {
            return context.physicalTableName();
        }
        return metadata.collectionName();
    }

    // ==================== 异常翻译 ====================

    /** MongoDB 官方可重试写错误码：选主切换 / 节点关闭 / 网络类，重试通常能成功。 */
    private static final Set<Integer> TRANSIENT_ERROR_CODES = Set.of(
            6,      // HostUnreachable
            7,      // HostNotFound
            89,     // NetworkTimeout
            91,     // ShutdownInProgress
            189,    // PrimarySteppedDown
            262,    // ExceededTimeLimit
            9001,   // SocketException
            10107,  // NotWritablePrimary
            11600,  // InterruptedAtShutdown
            11602,  // InterruptedDueToReplStateChange
            13435,  // NotPrimaryNoSecondaryOk
            13436   // NotPrimaryOrSecondary
    );

    private GameJpaException wrapMongoException(String operation, DocEntityMetadata metadata,
            ExecutorContext context, Object id, MongoException e) {
        ExecutorContext actualContext = context != null ? context : ExecutorContext.defaultContext();
        return translateMongoException(operation, resolveCollectionName(metadata, context),
                actualContext.dataSourceName(), id, e);
    }

    /**
     * Mongo 驱动异常 → game-jpa 异常分类，与 MySQL 执行器的 wrapSqlException 对齐：
     * 网络 / 选主切换 / 超时等瞬时错误 → {@link RetriableWriteException}（FlushScheduler 会重试到 maxRetries）；
     * 重复键 → {@link DuplicateKeyException}；其余按确定性失败包装（异步路径通知失败处理器后丢弃）。
     */
    static GameJpaException translateMongoException(String operation, String collection,
            String dataSourceName, Object id, MongoException e) {
        String target = operation + " failed: " + collection;
        if (e instanceof MongoBulkWriteException bulk) {
            List<BulkWriteError> errors = bulk.getWriteErrors();
            if (bulk.getWriteConcernError() == null && allDuplicateKey(errors)) {
                return new DuplicateKeyException(collection, id, e);
            }
            // write concern 未满足视为瞬时（数据可能已写入，重试的 upsert/delete 幂等）
            if (bulk.getWriteConcernError() != null || allTransient(errors)) {
                return new RetriableWriteException(target + " (transient bulk write)", e);
            }
            return new GameJpaException(target, e);
        }
        if (e instanceof MongoWriteException write) {
            int code = write.getError().getCode();
            if (ErrorCategory.fromErrorCode(code) == ErrorCategory.DUPLICATE_KEY) {
                return new DuplicateKeyException(collection, id, e);
            }
            if (TRANSIENT_ERROR_CODES.contains(code)) {
                return new RetriableWriteException(target + " (transient write error)", e);
            }
            return new GameJpaException(target, e);
        }
        if (e instanceof MongoSocketException || e instanceof MongoTimeoutException) {
            return new ConnectionException(target + " (connection error)", dataSourceName, e);
        }
        if (e instanceof MongoWriteConcernException
                || e.hasErrorLabel("RetryableWriteError")
                || e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                || TRANSIENT_ERROR_CODES.contains(e.getCode())) {
            return new RetriableWriteException(target + " (transient)", e);
        }
        return new GameJpaException(target, e);
    }

    private static boolean allDuplicateKey(List<BulkWriteError> errors) {
        if (errors.isEmpty()) {
            return false;
        }
        for (BulkWriteError error : errors) {
            if (ErrorCategory.fromErrorCode(error.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
        }
        return true;
    }

    private static boolean allTransient(List<BulkWriteError> errors) {
        if (errors.isEmpty()) {
            return false;
        }
        for (BulkWriteError error : errors) {
            if (!TRANSIENT_ERROR_CODES.contains(error.getCode())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> mapToDocument(DocEntityMetadata metadata, Object entity) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (DocFieldMetadata field : metadata.fields()) {
            Object value = field.accessor().get(entity);
            String key = field.isPrimaryKey() ? "_id" : field.documentFieldName();
            if (value != null) {
                document.put(key, toDocumentValue(field, value));
            }
        }
        return document;
    }

    private Object mapFromDocument(DocEntityMetadata metadata, Map<String, Object> document) {
        Object instance = ReflectionUtils.newInstance(metadata.entityType());
        for (DocFieldMetadata field : metadata.fields()) {
            String key = field.isPrimaryKey() ? "_id" : field.documentFieldName();
            Object value = document.get(key);
            if (value != null) {
                field.accessor().set(instance, fromDocumentValue(field, value));
            }
        }
        return instance;
    }

    private void ensureIndexes(DocEntityMetadata metadata, ExecutorContext context,
                               String collectionName, MongoCollection<Document> collection) {
        if (metadata.indexedFields().isEmpty()) {
            return;
        }
        ExecutorContext actualContext = context != null ? context : ExecutorContext.defaultContext();
        String key = actualContext.dataSourceName() + ":" + collectionName + ":" + metadata.entityType().getName();
        if (!ensuredIndexes.add(key)) {
            return;
        }
        for (DocFieldMetadata field : metadata.indexedFields()) {
            String fieldName = field.isPrimaryKey() ? "_id" : field.documentFieldName();
            collection.createIndex(new Document(fieldName, 1));
        }
    }

    private Object toDocumentValue(DocFieldMetadata field, Object value) {
        // 嵌套 POJO 自动映射为嵌套文档；注册了自定义 TypeConverter 的类型优先走转换器
        if (field.isEmbedded() && converterRegistry.findWrite(value.getClass()).isEmpty()) {
            return new Document(mapEmbeddedObject(value));
        }
        return converterRegistry.write(value);
    }

    private Object fromDocumentValue(DocFieldMetadata field, Object value) {
        if (field.isEmbedded() && value instanceof Map<?, ?> map
                && converterRegistry.findRead(field.javaType(), value.getClass()).isEmpty()) {
            return mapEmbeddedObject(field.javaType(), map);
        }
        return converterRegistry.read(value, field.javaType());
    }

    private Map<String, Object> mapEmbeddedObject(Object value) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (Field javaField : ReflectionUtils.getAllFields(value.getClass())) {
            if (!ReflectionUtils.isPersistentField(javaField)) {
                continue;
            }
            DocFieldMetadata embeddedField = embeddedField(javaField);
            Object nested = embeddedField.accessor().get(value);
            if (nested != null) {
                document.put(embeddedField.documentFieldName(), toDocumentValue(embeddedField, nested));
            }
        }
        return document;
    }

    private Object mapEmbeddedObject(Class<?> type, Map<?, ?> document) {
        Object instance = ReflectionUtils.newInstance(type);
        for (Field javaField : ReflectionUtils.getAllFields(type)) {
            if (!ReflectionUtils.isPersistentField(javaField)) {
                continue;
            }
            DocFieldMetadata embeddedField = embeddedField(javaField);
            Object value = document.get(embeddedField.documentFieldName());
            if (value != null) {
                embeddedField.accessor().set(instance, fromDocumentValue(embeddedField, value));
            }
        }
        return instance;
    }

    private Object storageId(DocEntityMetadata metadata, Object id) {
        return toDocumentValue(metadata.idField(), id);
    }

    private DocFieldMetadata embeddedField(Field javaField) {
        return embeddedFieldCache.computeIfAbsent(javaField, field -> {
            cn.managame.jpa.docdb.annotation.Field fieldAnn =
                    field.getAnnotation(cn.managame.jpa.docdb.annotation.Field.class);
            String name = fieldAnn != null && !fieldAnn.name().isEmpty()
                    ? fieldAnn.name()
                    : field.getName();
            return new DocFieldMetadata(field, field.getName(), name, field.getType(),
                    false, DocTypes.isEmbeddedDocument(field.getType()),
                    field.isAnnotationPresent(cn.managame.jpa.docdb.annotation.Indexed.class));
        });
    }

    private Map<String, Object> buildFilter(DocEntityMetadata metadata, DocQuerySpec spec) {
        Map<String, Object> filter = new LinkedHashMap<>();
        List<Map<String, Object>> andFilters = new ArrayList<>();
        for (DocQuerySpec.Filter f : spec.filters()) {
            String fieldName = resolveDocumentFieldName(metadata, f.field());
            Object condition = switch (f.op()) {
                case EQ -> converterRegistry.write(f.value());
                case NE -> Map.of("$ne", converterRegistry.write(f.value()));
                case IN -> Map.of("$in", convertCollection(f.value()));
                case EXISTS -> Map.of("$exists", f.value());
            };
            if (andFilters.isEmpty() && !filter.containsKey(fieldName)) {
                filter.put(fieldName, condition);
                continue;
            }
            if (andFilters.isEmpty()) {
                for (Map.Entry<String, Object> entry : filter.entrySet()) {
                    andFilters.add(singleFilter(entry.getKey(), entry.getValue()));
                }
                filter.clear();
            }
            andFilters.add(singleFilter(fieldName, condition));
        }
        if (!andFilters.isEmpty()) {
            filter.put("$and", andFilters);
        }
        return filter;
    }

    private Map<String, Object> singleFilter(String fieldName, Object condition) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put(fieldName, condition);
        return filter;
    }

    private Map<String, Integer> buildSort(DocEntityMetadata metadata, DocQuerySpec spec) {
        Map<String, Integer> sort = new LinkedHashMap<>();
        for (DocQuerySpec.Sort s : spec.sorts()) {
            sort.put(resolveDocumentFieldName(metadata, s.field()), s.ascending() ? 1 : -1);
        }
        return sort;
    }

    private Map<String, Object> buildUpdate(DocEntityMetadata metadata, DocUpdateSpec spec) {
        Map<String, Object> setMap = new LinkedHashMap<>();
        Map<String, Object> unsetMap = new LinkedHashMap<>();
        Map<String, Object> incMap = new LinkedHashMap<>();

        for (DocUpdateSpec.UpdateOp op : spec.operations()) {
            String fieldName = resolveDocumentFieldName(metadata, op.field());
            if ("_id".equals(fieldName)) {
                throw new IllegalArgumentException("Cannot update document id field: " + op.field());
            }
            switch (op.type()) {
                case SET -> setMap.put(fieldName, converterRegistry.write(op.value()));
                case UNSET -> unsetMap.put(fieldName, "");
                case INC -> incMap.put(fieldName, converterRegistry.write(op.value()));
            }
        }

        Map<String, Object> update = new LinkedHashMap<>();
        if (!setMap.isEmpty()) update.put("$set", setMap);
        if (!unsetMap.isEmpty()) update.put("$unset", unsetMap);
        if (!incMap.isEmpty()) update.put("$inc", incMap);
        return update;
    }

    private Object convertCollection(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<Object> converted = new ArrayList<>();
            for (Object item : iterable) {
                converted.add(converterRegistry.write(item));
            }
            return converted;
        }
        return converterRegistry.write(value);
    }

    private String resolveDocumentFieldName(DocEntityMetadata metadata, String field) {
        DocFieldMetadata fieldMetadata = metadata.fieldByPropertyName(field);
        if (fieldMetadata == null) {
            fieldMetadata = metadata.fieldByDocumentFieldName(field);
        }
        if (fieldMetadata == null) {
            throw new IllegalArgumentException("Unknown document property: " + field
                    + " for entity " + metadata.entityType().getName());
        }
        return fieldMetadata.isPrimaryKey() ? "_id" : fieldMetadata.documentFieldName();
    }
}
