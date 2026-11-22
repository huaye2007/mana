package cn.managame.ai;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Message boundary shared by outbound calls and tools. Supply a codec for a different wire representation. */
public interface AiJson {
    String encode(Object value);

    <T> T decode(String json, Class<T> type);

    /**
     * Decode an already-structured argument map (for example from an MCP request) into {@code type}.
     * Defaults to encoding and decoding through the JSON string boundary; codecs may convert directly.
     */
    default <T> T decode(Map<String, ?> arguments, Class<T> type) {
        if (arguments == null) throw new IllegalArgumentException("AI message must not be null");
        return decode(encode(arguments), type);
    }

    /** JSON Schema for an object accepted as tool arguments. Called at registration. */
    String inputSchema(Class<?> type);

    /**
     * Optional JSON Schema describing a response or tool output type, or {@code null} when the type is
     * unstructured or unsupported (such as {@code void} or {@code Object}).
     */
    default String outputSchema(Class<?> type) { return null; }

    /** Default POJO/record codec with strict scalar decoding and ISO-8601 java.time support. */
    static AiJson jackson() { return Jackson.DEFAULT; }

    /**
     * Strict POJO/record codec that encodes 64-bit {@code long}/{@code Long} values as JSON strings and describes
     * them as {@code string} ({@code int64}) in tool schemas, preventing precision loss for Snowflake IDs in JS/LLM clients.
     * Decoding accepts decimal integer strings as well as exact JSON integers.
     */
    static AiJson jacksonStringLongs() { return Jackson.STRING_LONGS; }

    /** Codec backed by an application-supplied {@link ObjectMapper}. */
    static AiJson jackson(ObjectMapper mapper) { return jackson(mapper, false); }

    /** Codec backed by an application-supplied {@link ObjectMapper}, optionally describing longs as strings. */
    static AiJson jackson(ObjectMapper mapper, boolean stringLongs) {
        return new Jackson(Objects.requireNonNull(mapper, "mapper"), stringLongs);
    }

    final class Jackson implements AiJson {
        private static final Jackson DEFAULT = new Jackson(defaultMapper(false), false);
        private static final Jackson STRING_LONGS = new Jackson(defaultMapper(true), true);
        private final ObjectMapper mapper;
        private final boolean stringLongs;

        private Jackson(ObjectMapper mapper, boolean stringLongs) {
            this.mapper = mapper;
            this.stringLongs = stringLongs;
        }

        private static ObjectMapper defaultMapper(boolean stringLongs) {
            SimpleModule module = new SimpleModule("managame-ai");
            registerTime(module, Instant.class, Instant::parse);
            registerTime(module, LocalDate.class, LocalDate::parse);
            registerTime(module, LocalDateTime.class, LocalDateTime::parse);
            registerTime(module, Duration.class, Duration::parse);
            if (stringLongs) {
                module.addSerializer(Long.class, new LongToStringSerializer(Long.class));
                module.addSerializer(long.class, new LongToStringSerializer(long.class));
                module.addDeserializer(Long.class, new StrictLongDeserializer(Long.class, false));
                module.addDeserializer(long.class, new StrictLongDeserializer(long.class, true));
            }
            return JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .addModule(module)
                    .build();
        }

        private static <T> void registerTime(SimpleModule module, Class<T> type, Function<String, T> parser) {
            module.addSerializer(type, new StdSerializer<>(type) {
                @Override public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeString(value.toString());
                }
            });
            module.addDeserializer(type, new StdDeserializer<>(type) {
                @Override public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    if (p.currentToken() != JsonToken.VALUE_STRING)
                        return type.cast(ctxt.handleUnexpectedToken(type, p));
                    try { return parser.apply(p.getText()); }
                    catch (DateTimeParseException e) { throw ctxt.weirdStringException(p.getText(), type, e.getMessage()); }
                }
            });
        }

        @Override public String encode(Object value) {
            try { return mapper.writeValueAsString(value); }
            catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode AI message", e); }
        }

        @Override public <T> T decode(String json, Class<T> type) {
            if (json == null) throw new IllegalArgumentException("AI message must not be null");
            try {
                T value = mapper.readValue(json, type);
                if (value == null) throw new IllegalArgumentException("AI message must not be null");
                return value;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid " + type.getSimpleName(), e);
            }
        }

        @Override public <T> T decode(Map<String, ?> arguments, Class<T> type) {
            if (arguments == null) throw new IllegalArgumentException("AI message must not be null");
            try {
                T value = mapper.convertValue(arguments, type);
                if (value == null) throw new IllegalArgumentException("AI message must not be null");
                return value;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid " + type.getSimpleName(), e);
            }
        }

        @Override public String inputSchema(Class<?> type) { return ToolSchema.describe(mapper, type, stringLongs); }

        @Override public String outputSchema(Class<?> type) { return ToolSchema.describeAny(mapper, type, stringLongs); }

        private static final class LongToStringSerializer extends StdSerializer<Long> {
            LongToStringSerializer(Class<Long> type) { super(type); }
            @Override public void serialize(Long value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(Long.toString(value));
            }
        }

        private static final class StrictLongDeserializer extends StdDeserializer<Long> {
            private final boolean primitive;
            StrictLongDeserializer(Class<?> type, boolean primitive) {
                super(type);
                this.primitive = primitive;
            }
            @Override public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) return p.getLongValue();
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    String text = p.getText();
                    try { return Long.parseLong(text); }
                    catch (NumberFormatException e) { throw ctxt.weirdStringException(text, Long.class, "Expected 64-bit integer"); }
                }
                return (Long) ctxt.handleUnexpectedToken(Long.class, p);
            }
            @Override public Long getNullValue(DeserializationContext ctxt) throws com.fasterxml.jackson.databind.JsonMappingException {
                if (primitive && ctxt.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES))
                    ctxt.reportInputMismatch(this, "Cannot map `null` into type `long`");
                return primitive ? 0L : null;
            }
        }
    }
}
