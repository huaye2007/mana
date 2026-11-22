package cn.managame.ai;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Optional ProtoJSON boundary for registered generated messages. Other types delegate to the fallback codec.
 * Tool schema generation supports ordinary acyclic messages. Supply a custom AiJson for specialized tool contracts. */
public final class ProtobufJson implements AiJson {
    private final Map<Class<?>, Message> types;
    private final AiJson fallback;
    private final JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
    private final JsonFormat.Parser parser = JsonFormat.parser();

    public ProtobufJson(Collection<? extends Message> prototypes) {
        this(prototypes, AiJson.jackson());
    }

    public ProtobufJson(Collection<? extends Message> prototypes, AiJson fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        Map<Class<?>, Message> registered = new HashMap<>();
        for (var value : prototypes) registered.put(value.getClass(), value.getDefaultInstanceForType());
        types = Map.copyOf(registered);
    }

    @Override public String encode(Object value) {
        if (!(value instanceof Message message)) return fallback.encode(value);
        try { return printer.print(message); }
        catch (InvalidProtocolBufferException e) { throw new IllegalArgumentException("Invalid protobuf message", e); }
    }

    @Override public <T> T decode(String json, Class<T> type) {
        if (!Message.class.isAssignableFrom(type)) return fallback.decode(json, type);
        if (json == null) throw new IllegalArgumentException("AI message must not be null");
        var builder = prototype(type).newBuilderForType();
        try { parser.merge(json, builder); return type.cast(builder.build()); }
        catch (InvalidProtocolBufferException e) { throw new IllegalArgumentException("Invalid ProtoJSON", e); }
    }

    @Override public <T> T decode(Map<String, ?> arguments, Class<T> type) {
        if (!Message.class.isAssignableFrom(type)) return fallback.decode(arguments, type);
        if (arguments == null) throw new IllegalArgumentException("AI message must not be null");
        return decode(fallback.encode(arguments), type);
    }

    @Override public String inputSchema(Class<?> type) {
        if (!Message.class.isAssignableFrom(type)) return fallback.inputSchema(type);
        return fallback.encode(message(prototype(type).getDescriptorForType(), new HashSet<>()));
    }

    @Override public String outputSchema(Class<?> type) {
        if (!Message.class.isAssignableFrom(type)) return fallback.outputSchema(type);
        Message proto = types.get(type);
        if (proto == null) return null;
        try {
            return fallback.encode(message(proto.getDescriptorForType(), new HashSet<>()));
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private Message prototype(Class<?> type) {
        Message value = types.get(type);
        if (value == null) throw new IllegalArgumentException("Missing protobuf prototype: " + type);
        return value;
    }

    private static Map<String, Object> message(Descriptor type, Set<Descriptor> visiting) {
        if (!visiting.add(type) || type.getFile().getPackage().equals("google.protobuf"))
            throw new IllegalArgumentException("Custom schema required for " + type.getFullName());
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var field : type.getFields()) {
            Map<String, Object> schema;
            if (field.isMapField()) schema = Map.of("type", "object", "additionalProperties",
                    scalar(field.getMessageType().findFieldByName("value"), visiting));
            else {
                schema = scalar(field, visiting);
                if (field.isRepeated()) schema = Map.of("type", "array", "items", schema);
            }
            properties.put(field.getJsonName(), schema);
            if (field.isRequired()) required.add(field.getJsonName());
        }
        visiting.remove(type);
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "properties", properties, "additionalProperties", false));
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> scalar(FieldDescriptor field, Set<Descriptor> visiting) {
        return switch (field.getJavaType()) {
            case MESSAGE -> message(field.getMessageType(), visiting);
            case ENUM -> Map.of("type", "string", "enum", field.getEnumType().getValues().stream().map(EnumValueDescriptor::getName).toList());
            case STRING -> Map.of("type", "string");
            case LONG -> Map.of("type", "string", "format", "int64");
            case BYTE_STRING -> Map.of("type", "string", "contentEncoding", "base64");
            case INT -> Map.of("type", "integer");
            case BOOLEAN -> Map.of("type", "boolean");
            case DOUBLE, FLOAT -> Map.of("type", "number");
        };
    }
}
