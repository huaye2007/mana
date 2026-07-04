package cn.managame.serialization.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationException;
import cn.managame.serialization.SerializationType;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class JacksonJsonSerializer implements ISerializer {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<Class<?>, ObjectWriter> writers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, ObjectReader> readers = new ConcurrentHashMap<>();

    public static JacksonJsonSerializer create() {
        return new JacksonJsonSerializer(defaultObjectMapper());
    }

    public static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new BlackbirdModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    public JacksonJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public SerializationType type() {
        return SerializationType.JSON;
    }

    @Override
    public <T> byte[] serialize(T value) {
        Objects.requireNonNull(value, "value");
        return serialize(value, value.getClass());
    }

    public byte[] serialize(Object value, Class<?> declaredType) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(declaredType, "declaredType");
        try {
            return writerFor(declaredType).writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize JSON value of type " + declaredType.getName(), e);
        }
    }

    @Override
    public <T> void serialize(T value, OutputStream out) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(out, "out");
        try {
            writerFor(value.getClass()).writeValue(out, value);
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize JSON value of type "
                + value.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(type, "type");
        try {
            return readerFor(type).readValue(payload);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize JSON payload as " + type.getName(), e);
        }
    }

    public <T> T deserialize(byte[] payload, TypeReference<T> typeReference) {
        Objects.requireNonNull(typeReference, "typeReference");
        JavaType javaType = objectMapper.getTypeFactory().constructType(typeReference.getType());
        return deserialize(payload, javaType);
    }

    public <T> T deserialize(byte[] payload, JavaType javaType) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(javaType, "javaType");
        try {
            return objectMapper.readerFor(javaType).readValue(payload);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize JSON payload as " + javaType, e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private ObjectWriter writerFor(Class<?> type) {
        return writers.computeIfAbsent(type, objectMapper::writerFor);
    }

    private ObjectReader readerFor(Class<?> type) {
        return readers.computeIfAbsent(type, objectMapper::readerFor);
    }
}
