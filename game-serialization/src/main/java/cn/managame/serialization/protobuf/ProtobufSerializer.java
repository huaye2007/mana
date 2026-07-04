package cn.managame.serialization.protobuf;

import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationException;
import cn.managame.serialization.SerializationType;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ProtobufSerializer implements ISerializer {

    private final ConcurrentMap<Class<?>, Parser<?>> parsers = new ConcurrentHashMap<>();

    public static ProtobufSerializer create() {
        return new ProtobufSerializer();
    }

    @Override
    public SerializationType type() {
        return SerializationType.PROTOBUF;
    }

    @Override
    public <T> byte[] serialize(T value) {
        Objects.requireNonNull(value, "value");
        if (!(value instanceof MessageLite message)) {
            throw new IllegalArgumentException("Protobuf serializer requires a MessageLite value: "
                + value.getClass().getName());
        }
        return message.toByteArray();
    }

    @Override
    public <T> void serialize(T value, OutputStream out) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(out, "out");
        if (!(value instanceof MessageLite message)) {
            throw new IllegalArgumentException("Protobuf serializer requires a MessageLite value: "
                + value.getClass().getName());
        }
        try {
            message.writeTo(out);
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize Protobuf value of type "
                + value.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(type, "type");
        if (!MessageLite.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Protobuf serializer requires a MessageLite type: " + type.getName());
        }
        try {
            Object parsed = parserFor(type).parseFrom(payload);
            return type.cast(parsed);
        } catch (InvalidProtocolBufferException e) {
            throw new SerializationException("Failed to deserialize Protobuf payload as " + type.getName(), e);
        }
    }

    public <T extends MessageLite> T deserialize(byte[] payload, Parser<T> parser) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(parser, "parser");
        try {
            return parser.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new SerializationException("Failed to deserialize Protobuf payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Parser<T> parserFor(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return (Parser<T>) parsers.computeIfAbsent(type, ProtobufSerializer::resolveParser);
    }

    private static Parser<?> resolveParser(Class<?> type) {
        Parser<?> parser = parserFromStaticMethod(type);
        if (parser != null) {
            return parser;
        }

        parser = parserFromDefaultInstance(type);
        if (parser != null) {
            return parser;
        }

        throw new SerializationException("Unable to resolve Protobuf parser for " + type.getName());
    }

    private static Parser<?> parserFromStaticMethod(Class<?> type) {
        try {
            Method method = type.getMethod("parser");
            if (!Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            Object parser = method.invoke(null);
            if (parser instanceof Parser<?> protobufParser) {
                return protobufParser;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new SerializationException("Failed to invoke parser() on " + type.getName(), e);
        }
    }

    private static Parser<?> parserFromDefaultInstance(Class<?> type) {
        try {
            Method method = type.getMethod("getDefaultInstance");
            if (!Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            Object defaultInstance = method.invoke(null);
            if (defaultInstance instanceof MessageLite message) {
                return message.getParserForType();
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new SerializationException("Failed to invoke getDefaultInstance() on " + type.getName(), e);
        }
    }
}
