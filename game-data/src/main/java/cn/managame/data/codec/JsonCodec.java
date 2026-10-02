package cn.managame.data.codec;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Function;
public interface JsonCodec {
    static JsonCodec defaultCodec() { return JacksonJsonCodec.INSTANCE; }
    String encode(Object value);
    Object decode(String value, Type type);
    /** Binds a field's complete type during mapping initialization. */
    default Function<String, Object> decoder(Type type) {
        Objects.requireNonNull(type);
        return value -> decode(value, type);
    }
    /** The initializer implementation is optional; custom codecs may choose their own representation. */
    default Function<String, Object> decoder(Type type, Class<?> initializedType) { return decoder(type); }
}
