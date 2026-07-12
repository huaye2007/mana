package cn.managame.serialization;

import cn.managame.serialization.fory.ForySerializer;
import cn.managame.serialization.json.JacksonJsonSerializer;
import cn.managame.serialization.protobuf.ProtobufSerializer;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Thread-safe serializer registry indexed directly by stable wire ids. */
public class SerializerManager {

    private static final SerializerManager INSTANCE = createDefault();

    private final AtomicReferenceArray<ISerializer> serializers =
        new AtomicReferenceArray<>(SerializationType.maxTypeId() + 1);

    /** Returns the process-wide registry containing all built-in serializers. */
    public static SerializerManager getInstance() {
        return INSTANCE;
    }

    /** Creates an independent registry containing all built-in serializers. */
    public static SerializerManager createDefault() {
        SerializerManager manager = new SerializerManager();
        manager.register(JacksonJsonSerializer.create());
        manager.register(ProtobufSerializer.create());
        manager.register(ForySerializer.create());
        return manager;
    }

    /** Registers or replaces the serializer for its declared type. */
    public void register(ISerializer serializer) {
        Objects.requireNonNull(serializer, "serializer");
        SerializationType type = Objects.requireNonNull(serializer.type(), "serializer.type()");
        serializers.set(Byte.toUnsignedInt(type.typeId()), serializer);
    }

    /**
     * Compatibility lookup used by the network stack. Returns {@code null} for an unknown id or
     * for a known type that has not been registered in this manager.
     */
    public ISerializer getISerializer(byte serialType) {
        SerializationType type = SerializationType.getSerializationType(serialType);
        return type == null ? null : getSerializer(type);
    }

    public ISerializer getSerializer(SerializationType type) {
        Objects.requireNonNull(type, "type");
        return serializers.get(Byte.toUnsignedInt(type.typeId()));
    }

    public ISerializer requireSerializer(byte serialType) {
        ISerializer serializer = getISerializer(serialType);
        if (serializer == null) {
            throw new NoSuchElementException("No serializer registered for wire type "
                + Byte.toUnsignedInt(serialType));
        }
        return serializer;
    }

    public ISerializer requireSerializer(SerializationType type) {
        Objects.requireNonNull(type, "type");
        ISerializer serializer = getSerializer(type);
        if (serializer == null) {
            throw new NoSuchElementException("No serializer registered for " + type);
        }
        return serializer;
    }
}
