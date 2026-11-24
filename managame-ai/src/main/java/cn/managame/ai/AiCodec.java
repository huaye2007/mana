package cn.managame.ai;

import cn.managame.ai.internal.BasicAiCodec;
import java.lang.reflect.Type;

/**
 * Encodes and decodes JSON payloads for {@link AiClient} and {@link cn.managame.ai.mcp.McpTools}, letting the
 * application plug in its own JSON library (for example Jackson):
 *
 * <pre>{@code
 * AiCodec jackson = new AiCodec() {
 *     public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
 *     public Object decode(String json, Type type) throws Exception {
 *         return mapper.readValue(json, mapper.constructType(type));
 *     }
 * };
 * }</pre>
 *
 * <p>{@link #basic()} handles scalars, enums, collections, maps, arrays, {@link java.util.Optional} and Java records
 * with no third-party dependency. Implementations must be thread-safe.
 */
public interface AiCodec {
    /** Serializes {@code value} to a JSON string. */
    String encode(Object value) throws Exception;

    /** Deserializes {@code json} into an instance of {@code type}. */
    Object decode(String json, Type type) throws Exception;

    /** Built-in zero-dependency codec for scalars, enums, collections, maps, arrays, Optional and records. */
    static AiCodec basic() { return BasicAiCodec.INSTANCE; }
}
