package cn.managame.serialization.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import cn.managame.serialization.SerializationException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonJsonSerializerTest {

    private final JacksonJsonSerializer serializer = JacksonJsonSerializer.create();

    @Test
    void roundTripsRecordPayload() {
        PlayerSnapshot snapshot = new PlayerSnapshot(
            10001L,
            "knight",
            42,
            Instant.parse("2026-06-07T00:00:00Z"),
            Map.of("gold", 500, "gem", 12)
        );

        byte[] payload = serializer.serialize(snapshot);
        PlayerSnapshot decoded = serializer.deserialize(payload, PlayerSnapshot.class);

        assertEquals(snapshot, decoded);
    }

    @Test
    void supportsGenericTypeReference() {
        byte[] payload = serializer.serialize(List.of("login", "move", "logout"));

        List<String> decoded = serializer.deserialize(payload, new TypeReference<>() {
        });

        assertEquals(List.of("login", "move", "logout"), decoded);
    }

    @Test
    void supportsDeclaredGenericTypeForSerialization() {
        TypeReference<Map<String, List<Integer>>> type = new TypeReference<>() {
        };
        Map<String, List<Integer>> value = Map.of("items", List.of(1, 3, 5));

        byte[] payload = serializer.serialize(value, type);

        assertEquals(value, serializer.deserialize(payload, type));
    }

    @Test
    void writesDirectlyToOutputStream() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        serializer.serialize(Map.of("online", 12), out);

        assertEquals("{\"online\":12}", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void wrapsMalformedPayloadWithSerializationException() {
        assertThrows(SerializationException.class,
            () -> serializer.deserialize("{".getBytes(StandardCharsets.UTF_8), Map.class));
    }

    @Test
    void rejectsTrailingJsonTokens() {
        byte[] payload = "{\"online\":12} {\"online\":13}"
            .getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializationException.class,
            () -> serializer.deserialize(payload, new TypeReference<Map<String, Integer>>() {
            }));
    }

    private record PlayerSnapshot(
        long playerId,
        String name,
        int level,
        Instant updatedAt,
        Map<String, Integer> wallet
    ) {
    }
}
