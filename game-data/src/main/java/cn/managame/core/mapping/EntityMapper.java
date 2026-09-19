package cn.managame.core.mapping;

import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.PropertyMetadata;
import cn.managame.core.metadata.StorageKind;

import java.util.Map;
import java.util.Objects;

/** Maps entity fields to backend values. JSON fields require an explicitly configured JsonCodec. */
public final class EntityMapper {
    private final ValueConverter converter;
    private final JsonCodec jsonCodec;

    public EntityMapper() { this(new DefaultValueConverter(), null); }

    public EntityMapper(ValueConverter converter) { this(converter, null); }

    public EntityMapper(ValueConverter converter, JsonCodec jsonCodec) {
        this.converter = Objects.requireNonNull(converter);
        this.jsonCodec = jsonCodec;
    }

    /** Checks mapping configuration once, before repositories start accepting mutations. */
    public void validate(EntityMetadata<?> metadata) {
        for (PropertyMetadata property : metadata.properties()) {
            try {
                if (property.storageKind() == StorageKind.JSON) requireJsonCodec(property);
                else converter.validate(property.type());
            } catch (RuntimeException error) {
                throw new DataException("Invalid mapping for " + metadata.type().getName() + "."
                        + property.propertyName() + ": " + error.getMessage(), error);
            }
        }
    }

    /** For non-property values such as IDs and query parameters. */
    public Object toStore(Object value, Class<?> declaredType) {
        return converter.toStore(value, declaredType);
    }

    public Object toStore(Object value, PropertyMetadata property) {
        if (value == null) return null;
        return switch (property.storageKind()) {
            case SCALAR, BINARY -> converter.toStore(value, property.type());
            case JSON -> requireJsonCodec(property).encode(value, property.genericType());
        };
    }

    public Object fromStore(Object value, PropertyMetadata property) {
        if (value == null) return converter.fromStore(null, property.type());
        return switch (property.storageKind()) {
            case SCALAR, BINARY -> converter.fromStore(value, property.type());
            case JSON -> {
                String json = value instanceof String s ? s : value.toString();
                yield requireJsonCodec(property).decode(json, property.genericType());
            }
        };
    }

    public Object fromStore(Object value, Class<?> targetType) {
        return converter.fromStore(value, targetType);
    }

    public <T> T fromRdb(EntityMetadata<T> metadata, Map<String, Object> row) {
        T entity = metadata.newInstance();
        for (PropertyMetadata p : metadata.properties()) {
            if (row.containsKey(p.rdbName())) p.set(entity, fromStore(row.get(p.rdbName()), p));
        }
        return entity;
    }

    public <T> T fromDocument(EntityMetadata<T> metadata, Map<String, Object> doc) {
        T entity = metadata.newInstance();
        for (PropertyMetadata p : metadata.properties()) {
            if (doc.containsKey(p.docName())) {
                Object raw = doc.get(p.docName());
                if (p.storageKind() == StorageKind.JSON && raw != null && !(raw instanceof String)) {
                    // Native document databases can keep structured values directly. If the runtime type
                    // already matches, avoid an unnecessary JSON round trip.
                    if (p.type().isInstance(raw)) p.set(entity, raw);
                    else p.set(entity, requireJsonCodec(p).decode(raw.toString(), p.genericType()));
                } else {
                    p.set(entity, fromStore(raw, p));
                }
            }
        }
        return entity;
    }

    public JsonCodec jsonCodec() { return jsonCodec; }

    private JsonCodec requireJsonCodec(PropertyMetadata property) {
        if (jsonCodec == null) {
            throw new DataException("JSON field requires a JsonCodec: "
                    + property.field().getDeclaringClass().getName() + "." + property.propertyName());
        }
        return jsonCodec;
    }
}
