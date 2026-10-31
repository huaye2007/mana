package cn.managame.data.mongo;

import cn.managame.data.spi.EntityType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;

/**
 * One field's value as BSON. Collections and maps are arrays and documents of their elements, created again as the
 * class the field is initialized with; anything else comes from the database's codec registry, where an application
 * registers its own classes.
 */
interface MongoValueCodec {
    BsonValue encode(Object value);

    Object decode(BsonValue value);

    static MongoValueCodec of(Type type, CodecRegistry registry) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(rawType)) return collectionCodec(rawType, of(arguments[0], registry));
            if (Map.class.isAssignableFrom(rawType)) return mapCodec(rawType, arguments[0], of(arguments[1], registry));
            throw new IllegalArgumentException("a generic Mongo field must be a collection or a map: " + type);
        }
        if (!(type instanceof Class<?> rawType)) throw new IllegalArgumentException("Mongo fields need a concrete type: " + type);
        Class<?> boxed = EntityType.boxed(rawType);
        if (boxed == Byte.class || boxed == Short.class || boxed == Float.class || boxed == Character.class)
            return byteShortFloatOrCharCodec(boxed);
        return registryCodec(boxed, registry);
    }

    private static MongoValueCodec collectionCodec(Class<?> rawType, MongoValueCodec elementCodec) {
        Supplier<Collection<Object>> newCollection = constructorOf(rawType, Set.class.isAssignableFrom(rawType) ? LinkedHashSet::new : ArrayList::new);
        return new MongoValueCodec() {
            @Override public BsonValue encode(Object value) {
                if (value == null) return BsonNull.VALUE;
                BsonArray array = new BsonArray();
                for (Object element : (Collection<?>) value) array.add(elementCodec.encode(element));
                return array;
            }

            @Override public Object decode(BsonValue value) {
                if (value == null || value.isNull()) return null;
                Collection<Object> collection = newCollection.get();
                for (BsonValue element : value.asArray()) collection.add(elementCodec.decode(element));
                return collection;
            }
        };
    }

    private static MongoValueCodec mapCodec(Class<?> rawType, Type keyType, MongoValueCodec valueCodec) {
        if (keyType != String.class && keyType != Integer.class && keyType != Long.class)
            throw new IllegalArgumentException("Mongo map keys must be String, Integer or Long, not " + keyType);
        Supplier<Map<Object, Object>> newMap = constructorOf(rawType, LinkedHashMap::new);
        return new MongoValueCodec() {
            @Override public BsonValue encode(Object value) {
                if (value == null) return BsonNull.VALUE;
                BsonDocument document = new BsonDocument();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                    document.append(Objects.requireNonNull(entry.getKey(), "map key").toString(), valueCodec.encode(entry.getValue()));
                return document;
            }

            @Override public Object decode(BsonValue value) {
                if (value == null || value.isNull()) return null;
                Map<Object, Object> map = newMap.get();
                for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
                    String name = entry.getKey();
                    Object key = keyType == Integer.class ? Integer.valueOf(name) : keyType == Long.class ? Long.valueOf(name) : name;
                    map.put(key, valueCodec.decode(entry.getValue()));
                }
                return map;
            }
        };
    }

    /** The class's public no-argument constructor, or {@code fallback} for an interface or abstract class. */
    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> constructorOf(Class<?> type, Supplier<T> fallback) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) return fallback;
        try {
            Constructor<?> constructor = type.getConstructor();
            return () -> {
                try {
                    return (T) constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot create " + type.getName(), e);
                }
            };
        } catch (NoSuchMethodException e) {
            return fallback;
        }
    }

    /** BSON has no byte, short, float or char: they are saved as int32, double and string. */
    private static MongoValueCodec byteShortFloatOrCharCodec(Class<?> type) {
        return new MongoValueCodec() {
            @Override public BsonValue encode(Object value) {
                if (value == null) return BsonNull.VALUE;
                if (type == Character.class) return new BsonString(value.toString());
                if (type == Float.class) return new BsonDouble(((Number) value).doubleValue());
                return new BsonInt32(((Number) value).intValue());
            }

            @Override public Object decode(BsonValue value) {
                if (value == null || value.isNull()) return null;
                if (type == Character.class) {
                    String text = value.asString().getValue();
                    if (text.length() != 1) throw new IllegalArgumentException("a char field needs one character, not \"" + text + "\"");
                    return text.charAt(0);
                }
                if (type == Float.class) return (float) value.asNumber().doubleValue();
                int number = value.asNumber().intValue();
                return type == Byte.class ? (Object) (byte) number : (Object) (short) number;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static MongoValueCodec registryCodec(Class<?> type, CodecRegistry registry) {
        Codec<Object> codec = (Codec<Object>) registry.get(type);
        return new MongoValueCodec() {
            @Override public BsonValue encode(Object value) {
                if (value == null) return BsonNull.VALUE;
                BsonDocument wrapper = new BsonDocument();
                try (BsonDocumentWriter writer = new BsonDocumentWriter(wrapper)) {
                    writer.writeStartDocument();
                    writer.writeName("v");
                    codec.encode(writer, value, EncoderContext.builder().build());
                    writer.writeEndDocument();
                }
                return wrapper.get("v");
            }

            @Override public Object decode(BsonValue value) {
                if (value == null || value.isNull()) return null;
                try (BsonDocumentReader reader = new BsonDocumentReader(new BsonDocument("v", value))) {
                    reader.readStartDocument();
                    reader.readBsonType();
                    reader.skipName();
                    Object decoded = codec.decode(reader, DecoderContext.builder().build());
                    reader.readEndDocument();
                    return decoded;
                }
            }
        };
    }
}
