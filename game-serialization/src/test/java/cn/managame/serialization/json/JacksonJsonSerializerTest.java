package cn.managame.serialization.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
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

    private record PlayerSnapshot(
        long playerId,
        String name,
        int level,
        Instant updatedAt,
        Map<String, Integer> wallet
    ) {
    }
}
