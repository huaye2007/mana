package cn.managame.data.codec;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.JavaType;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Function;

/** Immutable default configuration; field-bound readers are reusable across threads. */
final class JacksonJsonCodec implements JsonCodec {
    static final JsonCodec INSTANCE = new JacksonJsonCodec();
    private final ObjectMapper mapper = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JacksonJsonCodec() {}
    @Override public String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException cause) { throw new IllegalArgumentException("Cannot encode JSON value", cause); }
    }
    @Override public Object decode(String value, Type type) { return decoder(type).apply(value); }
    @Override public Function<String, Object> decoder(Type type) { return decoder(type, null); }
    @Override public Function<String, Object> decoder(Type type, Class<?> initializedType) {
        Objects.requireNonNull(type);
        JavaType declared = mapper.constructType(type);
        JavaType target = initializedType == null ? declared
                : mapper.getTypeFactory().constructSpecializedType(declared, initializedType);
        ObjectReader reader = mapper.readerFor(target);
        return value -> {
            try {
                Object decoded = reader.readValue(value);
                if (decoded != null && initializedType != null && !initializedType.isInstance(decoded))
                    throw new IllegalArgumentException("JSON decoder did not preserve initializer type " + initializedType.getName());
                return decoded;
            }
            catch (IOException cause) { throw new IllegalArgumentException("Cannot decode JSON as " + type.getTypeName(), cause); }
        };
    }
}
