package cn.managame.demo.serialization;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.memory.MemoryBuffer;

import java.util.Objects;

import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;

/** Shared TCP/RPC body serialization. Packet headers belong to the respective transports. */
public final class MessageSerializer {
    private static final ThreadSafeFory FORY = newSerializer();

    static ThreadSafeFory newSerializer() {
        var fory = Fory.builder().withXlang(true).withCompatible(true)
                .requireClassRegistration(true).withRefTracking(false)
                .withMaxDepth(32).withMaxGraphMemoryBytes(8 * 1024 * 1024)
                .buildThreadSafeForyPool(4);
        ClientCommands.MESSAGE_TYPES.forEach((id, type) -> fory.register(type, id));
        RpcCommands.MESSAGE_TYPES.forEach((id, type) -> fory.register(type, id));
        return fory;
    }

    public static byte[] serialize(Object message) {
        Objects.requireNonNull(message, "message");
        try {
            return FORY.serialize(message);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Cannot serialize message: " + message.getClass().getName(), failure);
        }
    }

    public static <T> T deserialize(byte[] bytes, Class<T> type) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(type, "type");
        try {
            return FORY.execute(fory -> {
                var buffer = MemoryBuffer.fromByteArray(bytes);
                Object message = fory.deserialize(buffer);
                if (buffer.readerIndex() != bytes.length)
                    throw new IllegalArgumentException("Trailing message body bytes");
                if (!type.isInstance(message))
                    throw new IllegalArgumentException("Expected message type: " + type.getName());
                return type.cast(message);
            });
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid message body for " + type.getName(), failure);
        }
    }

    /** Returns an owned buffer; the caller must release it after passing it to RPC. */
    public static ByteBuf serializeBuffer(Object message) {
        return Unpooled.wrappedBuffer(serialize(message));
    }

    /** Decodes a borrowed buffer without changing its indexes or reference count. */
    public static <T> T deserialize(ByteBuf body, Class<T> type) {
        return deserialize(ByteBufUtil.getBytes(body, body.readerIndex(), body.readableBytes(), true), type);
    }

    private MessageSerializer() {}
}
