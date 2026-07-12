package cn.managame.serialization.json;

import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationException;
import cn.managame.serialization.SerializationType;
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
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe Jackson serializer with cached, immutable readers and writers. */
public final class JacksonJsonSerializer implements ISerializer {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<JavaType, ObjectWriter> writers = new ConcurrentHashMap<>();
    private final ConcurrentMap<JavaType, ObjectReader> readers = new ConcurrentHashMap<>();

    public static JacksonJsonSerializer create() {
        return new JacksonJsonSerializer(defaultObjectMapper());
    }

    /** Creates the mapper used by the built-in serializer. */
    public static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new BlackbirdModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    /**
     * Creates a serializer around a configured mapper. The mapper must not be reconfigured after
     * the serializer starts handling traffic because cached readers and writers are snapshots.
     */
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
        return serialize(value, objectMapper.constructType(value.getClass()));
    }

    public byte[] serialize(Object value, Class<?> declaredType) {
        Objects.requireNonNull(declaredType, "declaredType");
        return serialize(value, objectMapper.constructType(declaredType));
    }

    public byte[] serialize(Object value, TypeReference<?> declaredType) {
        Objects.requireNonNull(declaredType, "declaredType");
        return serialize(value, objectMapper.constructType(declaredType.getType()));
    }

    public byte[] serialize(Object value, JavaType declaredType) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(declaredType, "declaredType");
        try {
            return writerFor(declaredType).writeValueAsBytes(value);
        } catch (JsonProcessingException | RuntimeException e) {
            throw serializationFailure(value, e);
        }
    }

    @Override
    public <T> void serialize(T value, OutputStream out) {
        Objects.requireNonNull(value, "value");
        serialize(value, objectMapper.constructType(value.getClass()), out);
    }

    public void serialize(Object value, JavaType declaredType, OutputStream out) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(declaredType, "declaredType");
        Objects.requireNonNull(out, "out");
        try {
            writerFor(declaredType).writeValue(out, value);
        } catch (IOException | RuntimeException e) {
            throw serializationFailure(value, e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return deserialize(payload, objectMapper.constructType(type));
    }

    public <T> T deserialize(byte[] payload, TypeReference<T> typeReference) {
        Objects.requireNonNull(typeReference, "typeReference");
        return deserialize(payload, objectMapper.constructType(typeReference.getType()));
    }

    public <T> T deserialize(byte[] payload, JavaType javaType) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(javaType, "javaType");
        try {
            return readerFor(javaType).readValue(payload);
        } catch (IOException | RuntimeException e) {
            throw new SerializationException("Failed to deserialize JSON payload as " + javaType, e);
        }
    }

    /** Returns the configured mapper. Configure it only before the first serialization call. */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private ObjectWriter writerFor(JavaType type) {
        return writers.computeIfAbsent(type, objectMapper::writerFor);
    }

    private ObjectReader readerFor(JavaType type) {
        return readers.computeIfAbsent(type, objectMapper::readerFor);
    }

    private static SerializationException serializationFailure(Object value, Exception cause) {
        return new SerializationException("Failed to serialize JSON value of type "
            + value.getClass().getName(), cause);
    }
}
