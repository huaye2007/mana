package cn.managame.ai;

import cn.managame.ai.internal.BasicJsonCodec;
import java.lang.reflect.Type;

/**
 * Turns application DTOs (records, POJOs, collections) into JSON strings and back, for {@link AiClient} request and
 * response bodies as well as {@link AiTools} arguments and return values.
 *
 * <p>The application provides an implementation over its own JSON library (such as Jackson or Gson); it may be called
 * concurrently from HTTP client, Netty EventLoop and tool execution threads. When no codec is configured,
 * {@link #basic()} handles {@link String}, primitives, boxed numbers and booleans.
 */
public interface AiJsonCodec {
    /** Encodes {@code value} as a JSON string. */
    String encode(Object value) throws Exception;

    /** Decodes {@code json} into an instance of {@code type}. */
    Object decode(String json, Type type) throws Exception;

    /** Built-in zero-dependency codec for {@code String}, primitives, boxed numbers and booleans. */
    static AiJsonCodec basic() { return BasicJsonCodec.INSTANCE; }
}
