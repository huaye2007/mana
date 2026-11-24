package cn.managame.ai;

import cn.managame.ai.internal.BasicAiCodec;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes JSON payloads for {@link AiClient} and {@link cn.managame.ai.mcp.McpTools}, letting the
 * application plug in its own JSON library (for example Jackson):
 *
 * <pre>{@code
 * AiCodec jackson = new AiCodec() {
 *     public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
 *     public byte[] encodeBytes(Object value) throws Exception { return mapper.writeValueAsBytes(value); }
 *     public Object decode(String json, Type type) throws Exception {
 *         return mapper.readValue(json, mapper.constructType(type));
 *     }
 *     public Object decodeBytes(byte[] utf8, Type type) throws Exception {
 *         return mapper.readValue(utf8, mapper.constructType(type));
 *     }
 * };
 * }</pre>
 *
 * <p>{@link #basic()} handles scalars, enums, collections, maps, arrays, {@link java.util.Optional} and Java records
 * with no third-party dependency. Overriding {@link #encodeBytes} and {@link #decodeBytes} avoids intermediate
 * {@link String} copies on {@link AiClient} HTTP requests and responses. Implementations must be thread-safe.
 */
public interface AiCodec {
    /** Serializes {@code value} to a JSON string. */
    String encode(Object value) throws Exception;

    /** Deserializes {@code json} into an instance of {@code type}. */
    Object decode(String json, Type type) throws Exception;

    /** Serializes {@code value} directly to UTF-8 JSON bytes; defaults to {@link #encode} encoded as UTF-8. */
    default byte[] encodeBytes(Object value) throws Exception {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }

    /** Deserializes UTF-8 JSON bytes directly into an instance of {@code type}; defaults to {@link #decode}. */
    default Object decodeBytes(byte[] utf8, Type type) throws Exception {
        return decode(new String(utf8, StandardCharsets.UTF_8), type);
    }

    /** Built-in zero-dependency codec for scalars, enums, collections, maps, arrays, Optional and records. */
    static AiCodec basic() { return BasicAiCodec.INSTANCE; }
}
