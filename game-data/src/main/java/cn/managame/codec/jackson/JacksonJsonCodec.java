package cn.managame.codec.jackson;

import cn.managame.core.DataException;
import cn.managame.core.mapping.JsonCodec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.Objects;

/** Jackson implementation for List/Map/custom-object JSON fields. */
public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper objectMapper;

    public JacksonJsonCodec() { this(new ObjectMapper()); }

    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public String encode(Object value, Type declaredType) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DataException("JSON encode failed for " + declaredType.getTypeName(), e);
        }
    }

    @Override
    public Object decode(String json, Type declaredType) {
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(declaredType);
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new DataException("JSON decode failed for " + declaredType.getTypeName(), e);
        }
    }

    public ObjectMapper objectMapper() { return objectMapper; }
}
