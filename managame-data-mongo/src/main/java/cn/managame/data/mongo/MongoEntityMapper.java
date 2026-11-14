package cn.managame.data.mongo;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;

import cn.managame.data.DataException;
import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.mongo.annotation.Collection;
import cn.managame.data.mongo.annotation.Field;
import cn.managame.data.spi.Change;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;
import org.bson.codecs.configuration.CodecRegistry;

/**
 * Keeps entities in one MongoDB database, each entity type in its {@link Collection}, its {@code @Id} as {@code _id}
 * and every other saved field named after the field in snake case or by {@link Field}. Collections and maps are saved
 * as arrays and documents; other classes through the database's codec registry, where the application registers its
 * own. A field missing from a document keeps the value the constructor gives it.
 *
 * <p>Every write is idempotent, so a batch that failed part way is simply written again: an insert, and a delete
 * followed by an insert, replace the document with its {@code _id} or create it, so an insert also replaces a document
 * another writer stored with that {@code _id}; an update replaces the document and fails when it is missing; a delete
 * removes the documents.
 */
public final class MongoEntityMapper implements EntityMapper {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ReplaceOptions REPLACE_EXISTING = new ReplaceOptions(), REPLACE_OR_CREATE = new ReplaceOptions().upsert(true);
    private static final System.Logger LOG = System.getLogger("cn.managame.data.mongo");
    private final MongoDatabase database;
    private final CodecRegistry codecRegistry;
    private final boolean createMissing;
    private final ConcurrentHashMap<EntityType, CollectionMapping> collections = new ConcurrentHashMap<>();

    /** Creates the unique index a grouped type's collection lacks; {@link #builder} makes a mapper that creates none. */
    public MongoEntityMapper(MongoDatabase database) {
        this(Objects.requireNonNull(database, "database"), database.getCodecRegistry(), true);
    }

    /** {@code database} may be null for a mapper that only encodes and decodes. */
    MongoEntityMapper(MongoDatabase database, CodecRegistry codecRegistry) { this(database, codecRegistry, true); }

    private MongoEntityMapper(MongoDatabase database, CodecRegistry codecRegistry, boolean createMissing) {
        this.database = database;
        this.codecRegistry = codecRegistry;
        this.createMissing = createMissing;
    }

    /** A mapper with the setting the constructor leaves at its default. */
    public static Builder builder(MongoDatabase database) { return new Builder(Objects.requireNonNull(database, "database")); }

    public static final class Builder {
        private final MongoDatabase database;
        private boolean createMissing = true;

        private Builder(MongoDatabase database) { this.database = database; }

        /**
         * Whether the mapper creates, while GameData is built, the unique index on its group and map key fields that a
         * grouped type's collection lacks: every load of a group searches by the group fields. True by default. With
         * false the indexes are the application's alone, and a collection without one on its group fields is warned of.
         * MongoDB creates collections and fields by itself.
         */
        public Builder createMissing(boolean create) {
            createMissing = create;
            return this;
        }

        public MongoEntityMapper build() { return new MongoEntityMapper(database, database.getCodecRegistry(), createMissing); }
    }

    /**
     * Maps {@code type}'s fields to document fields, and for a grouped type creates the unique index its collection
     * lacks, unless told not to. IllegalArgumentException when the class has no {@code @Collection}, two fields of one
     * name, or a field the codec registry cannot save; DataException ({@code DATA_LOAD_FAILED}) when the index cannot be
     * created.
     */
    @Override public void prepare(EntityType type) {
        CollectionMapping mapping = new CollectionMapping(type);
        if (database != null && !mapping.groupFields.isEmpty()) createGroupIndex(mapping);
        collections.put(type, mapping);
    }

    /**
     * Creates a unique index on the group and map key fields, unless an index of the collection already starts with the
     * group fields; a mapper that creates nothing warns instead.
     */
    private void createGroupIndex(CollectionMapping mapping) {
        Set<String> groupNames = new HashSet<>();
        for (MongoField field : mapping.groupFields) groupNames.add(field.name);
        BsonDocument keys = new BsonDocument();
        for (MongoField field : mapping.groupFields) keys.append(field.name, new BsonInt32(1));
        for (EntityField mapKey : mapping.type.mapKeys()) keys.append(mapping.fieldOf(mapKey).name, new BsonInt32(1));
        try {
            for (BsonDocument index : mapping.collection.listIndexes(BsonDocument.class)) {
                List<String> fields = new ArrayList<>(index.getDocument("key").keySet());
                if (fields.size() >= groupNames.size() && groupNames.equals(new HashSet<>(fields.subList(0, groupNames.size())))) return;
            }
            if (!createMissing) {
                LOG.log(System.Logger.Level.WARNING, "Collection " + mapping.name + " has no index that starts with " + groupNames
                        + ": every load of a group reads the whole collection");
                return;
            }
            mapping.collection.createIndex(keys, new IndexOptions().unique(true));
            LOG.log(System.Logger.Level.INFO, "Created the unique index of collection " + mapping.name + " on " + keys.keySet());
        } catch (RuntimeException e) {
            if (!createMissing) {   // nothing was to be created: the indexes could not be read, which is no reason to fail
                LOG.log(System.Logger.Level.DEBUG, () -> "cannot read the indexes of collection " + mapping.name + ": " + e);
                return;
            }
            throw new DataException(DATA_LOAD_FAILED, "cannot create the unique index of collection " + mapping.name + " on " + keys.keySet()
                    + "; give the account the right to, or build the mapper with createMissing(false)", e);
        }
    }

    @Override public Object load(EntityType type, Object id) {
        CollectionMapping mapping = mappingOf(type);
        return mapping.decode(mapping.collection.find(new BsonDocument("_id", mapping.idField.codec.encode(id))).first());
    }

    /** One query by {@code _id $in}. */
    @Override public List<Object> loadAll(EntityType type, java.util.Collection<?> ids) {
        CollectionMapping mapping = mappingOf(type);
        BsonArray encoded = new BsonArray();
        for (Object id : ids) encoded.add(mapping.idField.codec.encode(id));
        List<Object> entities = new ArrayList<>(ids.size());
        for (BsonDocument document : mapping.collection.find(new BsonDocument("_id", new BsonDocument("$in", encoded))))
            entities.add(mapping.decode(document));
        return entities;
    }

    /** Every document of the collection. */
    @Override public List<Object> loadTable(EntityType type) {
        CollectionMapping mapping = mappingOf(type);
        List<Object> entities = new ArrayList<>();
        for (BsonDocument document : mapping.collection.find()) entities.add(mapping.decode(document));
        return entities;
    }

    @Override public List<Object> loadGroup(EntityType type, GroupKeys key) {
        CollectionMapping mapping = mappingOf(type);
        BsonDocument filter = new BsonDocument();
        for (int i = 0; i < key.size(); i++) {
            MongoField field = mapping.groupFields.get(i);
            filter.append(field.name, field.codec.encode(key.valueAt(i)));
        }
        List<Object> entities = new ArrayList<>();
        for (BsonDocument document : mapping.collection.find(filter)) entities.add(mapping.decode(document));
        return entities;
    }

    /** An update that finds fewer documents than it replaces fails as a whole; split into single writes, the missing one is reported. */
    @Override public void write(EntityType type, DataOperation operation, List<? extends Change> batch) {
        CollectionMapping mapping = mappingOf(type);
        switch (operation) {
            case INSERT, DELETE_INSERT -> mapping.collection.bulkWrite(mapping.replaces(batch, REPLACE_OR_CREATE));
            case UPDATE -> {
                BulkWriteResult result = mapping.collection.bulkWrite(mapping.replaces(batch, REPLACE_EXISTING));
                if (result.getMatchedCount() != batch.size())
                    throw new IllegalStateException("UPDATE of " + mapping.name + " matched " + result.getMatchedCount()
                            + " of " + batch.size() + " documents");
            }
            case DELETE -> {
                BsonArray ids = new BsonArray();
                for (Change change : batch) ids.add(mapping.idField.codec.encode(change.id()));
                mapping.collection.deleteMany(new BsonDocument("_id", new BsonDocument("$in", ids)));
            }
            case LOG_INSERT -> throw new IllegalArgumentException(operation.name());
        }
    }

    BsonDocument encode(EntityType type, Object entity) { return mappingOf(type).encode(entity); }

    Object decode(EntityType type, BsonDocument document) { return mappingOf(type).decode(document); }

    private CollectionMapping mappingOf(EntityType type) {
        CollectionMapping mapping = collections.get(type);
        if (mapping == null) throw new IllegalStateException(type + " was not prepared by this mapper");
        return mapping;
    }

    private record MongoField(EntityField field, String name, MongoValueCodec codec) {}

    /** One entity type's collection and how its fields map to document fields. */
    private final class CollectionMapping {
        final EntityType type;
        final String name;
        final MongoCollection<BsonDocument> collection;
        final List<MongoField> fields, groupFields;
        final MongoField idField;

        CollectionMapping(EntityType type) {
            this.type = type;
            Collection annotation = type.entityClass().getAnnotation(Collection.class);
            if (annotation == null) throw new IllegalArgumentException(type + " needs @Collection to be kept in MongoDB");
            name = checkName(annotation.name());
            collection = database == null ? null : database.getCollection(name, BsonDocument.class);
            List<MongoField> mapped = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (EntityField field : type.fields()) {
                Field rename = field.field().getAnnotation(Field.class);
                boolean isId = field == type.id();
                if (isId && rename != null && !rename.name().equals("_id"))
                    throw new IllegalArgumentException("@Id " + field.field() + " is always saved as _id");
                String fieldName = isId ? "_id" : checkName(rename == null ? snakeCase(field.name()) : rename.name());
                if (!names.add(fieldName)) throw new IllegalArgumentException(type + " has two fields named " + fieldName);
                try {
                    mapped.add(new MongoField(field, fieldName, MongoValueCodec.of(field.valueType(), codecRegistry)));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("cannot save " + field.field() + " in MongoDB", e);
                }
            }
            fields = List.copyOf(mapped);
            idField = fieldOf(type.id());
            groupFields = type.groupKeys().stream().map(this::fieldOf).toList();
        }

        BsonDocument encode(Object entity) {
            BsonDocument document = new BsonDocument();
            for (MongoField field : fields) {
                try {
                    document.append(field.name, field.codec.encode(field.field.get(entity)));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("cannot encode " + field.field.field() + " as BSON", e);
                }
            }
            return document;
        }

        Object decode(BsonDocument document) {
            if (document == null) return null;
            Object entity = type.newInstance();
            for (MongoField field : fields) {
                BsonValue value = document.get(field.name);
                if (value == null) continue;
                Object decoded;
                try {
                    decoded = field.codec.decode(value);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("cannot decode " + field.field.field() + " from BSON", e);
                }
                field.field.set(entity, decoded);
            }
            return entity;
        }

        List<WriteModel<BsonDocument>> replaces(List<? extends Change> batch, ReplaceOptions options) {
            List<WriteModel<BsonDocument>> models = new ArrayList<>(batch.size());
            for (Change change : batch) {
                BsonDocument document = encode(change.entity());
                models.add(new ReplaceOneModel<>(new BsonDocument("_id", document.get("_id")), document, options));
            }
            return models;
        }

        private MongoField fieldOf(EntityField field) {
            for (MongoField mapped : fields) if (mapped.field == field) return mapped;
            throw new IllegalStateException("no document field for " + field.field());
        }
    }

    private static String checkName(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("not a usable MongoDB name: " + name);
        return name;
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
