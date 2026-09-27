package cn.managame.data.mongo;

import cn.managame.data.meta.*;
import java.util.*;
import org.bson.*;
import org.bson.codecs.configuration.CodecRegistry;

final class MongoEntityMeta {
    record Property(FieldAccessor accessor, String name, MongoValueCodec codec) {}
    final EntityMeta entity;
    final String collection;
    final List<Property> fields, groups;
    final Property id;
    final List<MongoAccess.IndexDefinition> indexes;
    MongoEntityMeta(EntityMeta entity, CodecRegistry registry) {
        this.entity = entity;
        Collection annotation = entity.entityType().getAnnotation(Collection.class);
        if (annotation == null) throw new IllegalArgumentException("@Collection required");
        collection = name(annotation.name());
        List<Property> properties = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var field : entity.fields()) {
            Field mapping = field.getAnnotation(Field.class);
            if (mapping == null) continue;
            boolean isId = field.equals(entity.idField().field());
            if (isId && !mapping.name().isEmpty() && !mapping.name().equals("_id"))
                throw new IllegalArgumentException("@Id must map to _id");
            String column = isId ? "_id" : mapping.name().isEmpty() ? snake(field.getName()) : mapping.name();
            name(column);
            if (!isId && column.equals("_id") || !names.add(column))
                throw new IllegalArgumentException("Duplicate/reserved Mongo field: " + column);
            properties.add(new Property(new FieldAccessor(field), column, MongoValueCodec.of(field.getGenericType(), registry)));
        }
        fields = List.copyOf(properties);
        Map<java.lang.reflect.Field,Property> byField = new HashMap<>();
        fields.forEach(f -> byField.put(f.accessor().field(), f));
        for (var identity : entity.identityFields()) if (!byField.containsKey(identity.field()))
            throw new IllegalArgumentException("Identity requires @Field: " + identity.field());
        id = byField.get(entity.idField().field());
        groups = entity.groupFields().stream().map(f -> byField.get(f.field())).toList();
        Set<String> indexNames = new HashSet<>();
        List<MongoAccess.IndexDefinition> compiled = new ArrayList<>();
        for (MongoIndex index : annotation.indexes()) {
            List<String> keys = List.of(index.fields());
            if (keys.isEmpty() || !names.containsAll(keys) || new HashSet<>(keys).size() != keys.size())
                throw new IllegalArgumentException("Invalid Mongo index fields");
            String indexName = index.name().isEmpty() ? "idx_" + collection + "_" + String.join("_", keys) : name(index.name());
            if (indexName.equals("_id_") || !indexNames.add(indexName)) throw new IllegalArgumentException("Duplicate/reserved index name");
            compiled.add(new MongoAccess.IndexDefinition(indexName, keys, index.unique()));
        }
        indexes = List.copyOf(compiled);
    }
    private static String snake(String value) {
        return value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
    private static String name(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid Mongo storage name: " + name);
        return name;
    }
    BsonDocument encode(Object entity) {
        BsonDocument result = new BsonDocument();
        for (Property p : fields) result.append(p.name(), p.codec().encode(p.accessor().get(entity)));
        return result;
    }
    Object decode(BsonDocument document) {
        if (document == null) return null;
        Object result = entity.create();
        for (Property p : fields) {
            BsonValue value = document.get(p.name());
            if (value == null) continue; // Added fields retain constructor/Java defaults.
            Object decoded = p.codec().decode(value);
            if (decoded == null && p.accessor().type().isPrimitive())
                throw new IllegalArgumentException("BSON null for primitive: " + p.name());
            p.accessor().set(result, decoded);
        }
        return result;
    }
}
