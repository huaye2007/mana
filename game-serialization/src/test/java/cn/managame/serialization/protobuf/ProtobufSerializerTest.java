package cn.managame.serialization.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.serialization.SerializationException;
import com.google.protobuf.StringValue;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class ProtobufSerializerTest {

    private final ProtobufSerializer serializer = ProtobufSerializer.create();

    @Test
    void roundTripsGeneratedMessage() {
        StringValue value = StringValue.of("player-login");

        byte[] payload = serializer.serialize(value);
        StringValue decoded = serializer.deserialize(payload, StringValue.class);

        assertEquals(value, decoded);
    }

    @Test
    void rejectsNonProtobufPayloads() {
        assertThrows(IllegalArgumentException.class, () -> serializer.serialize("not-protobuf"));
    }

    @Test
    void writesDirectlyToOutputStream() {
        StringValue value = StringValue.of("streamed");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        serializer.serialize(value, out);

        assertEquals(value, serializer.deserialize(out.toByteArray(), StringValue.class));
    }

    @Test
    void wrapsMalformedPayloadWithSerializationException() {
        assertThrows(SerializationException.class,
            () -> serializer.deserialize(new byte[] {10, 5, 65}, StringValue.class));
    }
}
