package cn.managame.serialization;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Common serializer contract for network frames, cache values, and internal messages. */
public interface ISerializer {

    SerializationType type();

    <T> byte[] serialize(T value);

    <T> T deserialize(byte[] payload, Class<T> type);

    /**
     * Writes a value to a stream. Implementations with native stream support should override this
     * method; the default implementation writes the result of {@link #serialize(Object)}.
     */
    default <T> void serialize(T value, OutputStream out) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(out, "out");
        byte[] payload = serialize(value);
        try {
            out.write(payload);
        } catch (IOException e) {
            throw new SerializationException("Failed to write serialized value of type "
                + value.getClass().getName(), e);
        }
    }
}
