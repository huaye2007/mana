package cn.managame.data.mongo;

import cn.managame.data.meta.EntityMeta;
import java.lang.reflect.*;
import java.util.*;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;

interface MongoValueCodec {
    BsonValue encode(Object value);
    Object decode(BsonValue value);
    static MongoValueCodec of(Type type, CodecRegistry registry) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] args = parameterized.getActualTypeArguments();
            if (raw == List.class || raw == java.util.Collection.class || raw == Set.class) {
                MongoValueCodec item = of(args[0], registry);
                return new MongoValueCodec() {
                    public BsonValue encode(Object value) {
                        if (value == null) return BsonNull.VALUE;
                        var result = new BsonArray();
                        for (Object element : (java.util.Collection<?>) value) result.add(item.encode(element));
                        return result;
                    }
                    public Object decode(BsonValue value) {
                        if (value == null || value.isNull()) return null;
                        java.util.Collection<Object> result = raw == Set.class ? new LinkedHashSet<>() : new ArrayList<>();
                        value.asArray().forEach(v -> result.add(item.decode(v))); return result;
                    }
                };
            }
            if (raw == Map.class) {
                Type key = args[0];
                if (!(key == String.class || key == Integer.class || key == Long.class))
                    throw new IllegalArgumentException("Mongo map keys require String/Integer/Long: " + type);
                MongoValueCodec item = of(args[1], registry);
                return new MongoValueCodec() {
                    public BsonValue encode(Object value) {
                        if (value == null) return BsonNull.VALUE;
                        BsonDocument result = new BsonDocument();
                        ((Map<?,?>) value).forEach((k,v) -> {
                            String name = Objects.requireNonNull(k).toString();
                            if (name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid BSON map key");
                            result.append(name, item.encode(v));
                        });
                        return result;
                    }
                    public Object decode(BsonValue value) {
                        if (value == null || value.isNull()) return null;
                        Map<Object,Object> result = new LinkedHashMap<>();
                        value.asDocument().forEach((k,v) -> {
                            Object decodedKey;
                            if (key == Integer.class) decodedKey = Integer.valueOf(k);
                            else if (key == Long.class) decodedKey = Long.valueOf(k);
                            else decodedKey = k;
                            result.put(decodedKey, item.decode(v));
                        }); return result;
                    }
                };
            }
            throw new IllegalArgumentException("Unsupported parameterized Mongo field; use List/Set/Map or a concrete codec type: " + type);
        }
        if (!(type instanceof Class<?> raw)) throw new IllegalArgumentException("Concrete Mongo field type required: " + type);
        Class<?> c = EntityMeta.boxed(raw);
        if (c == Byte.class || c == Short.class || c == Float.class || c == Character.class) {
            return new MongoValueCodec() {
                public BsonValue encode(Object value) {
                    if (value == null) return BsonNull.VALUE;
                    if (c == Character.class) return new BsonString(value.toString());
                    if (c == Float.class) return new BsonDouble(((Number) value).doubleValue());
                    return new BsonInt32(((Number) value).intValue());
                }
                public Object decode(BsonValue value) {
                    if (value == null || value.isNull()) return null;
                    if (c == Character.class) {
                        String s = value.asString().getValue();
                        if (s.length() != 1) throw new IllegalArgumentException("BSON char must contain one character");
                        return s.charAt(0);
                    }
                    if (c == Float.class) return (float) value.asNumber().doubleValue();
                    int n = value.asNumber().intValue();
                    if (c == Byte.class) return (byte) n;
                    return (short) n;
                }
            };
        }
        return fromRegistry(c, registry);
    }
    @SuppressWarnings("unchecked")
    private static MongoValueCodec fromRegistry(Class<?> type, CodecRegistry registry) {
        Codec<Object> codec = (Codec<Object>) registry.get(type);
        return new MongoValueCodec() {
            public BsonValue encode(Object value) {
                if (value == null) return BsonNull.VALUE;
                BsonDocument wrapper = new BsonDocument();
                try (var writer = new BsonDocumentWriter(wrapper)) {
                    writer.writeStartDocument(); writer.writeName("v");
                    codec.encode(writer, value, EncoderContext.builder().build());
                    writer.writeEndDocument();
                }
                return wrapper.get("v");
            }
            public Object decode(BsonValue value) {
                if (value == null || value.isNull()) return null;
                try (var reader = new BsonDocumentReader(new BsonDocument("v", value))) {
                    reader.readStartDocument(); reader.readBsonType(); reader.readName();
                    Object result = codec.decode(reader, DecoderContext.builder().build());
                    reader.readEndDocument(); return result;
                }
            }
        };
    }
}
