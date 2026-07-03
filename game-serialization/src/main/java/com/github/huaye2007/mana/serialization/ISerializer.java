package com.github.huaye2007.mana.serialization;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Common serializer contract for network frames, cache values, and internal game messages.
 */
public interface ISerializer {

    SerializationType type();

    <T> byte[] serialize(T value);

    <T> T deserialize(byte[] array, Class<T> type);

    /**
     * Serialize directly into the given stream, avoiding the intermediate byte[] copy
     * on hot paths (e.g. encoding into a Netty ByteBuf). Implementations that can write
     * to a stream natively should override; the default falls back to {@link #serialize}.
     */
    default <T> void serialize(T value, OutputStream out) {
        byte[] bytes = serialize(value);
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw new SerializationException("Failed to write serialized value of type "
                    + value.getClass().getName(), e);
        }
    }

}
