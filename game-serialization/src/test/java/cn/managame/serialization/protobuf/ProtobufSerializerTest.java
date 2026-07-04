package cn.managame.serialization.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.StringValue;
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
}
