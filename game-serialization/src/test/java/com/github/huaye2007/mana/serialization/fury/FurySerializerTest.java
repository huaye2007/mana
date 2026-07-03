package com.github.huaye2007.mana.serialization.fury;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class FurySerializerTest {

    @Test
    void roundTripsPojoPayload() {
        FurySerializer serializer = FurySerializer.builder()
            .register(PlayerState.class, 100)
            .requireClassRegistration(true)
            .build();
        PlayerState state = new PlayerState(10001L, "mage", 37, new ArrayList<>(List.of(1, 2, 5, 8)));

        byte[] payload = serializer.serialize(state);
        PlayerState decoded = serializer.deserialize(payload, PlayerState.class);

        assertEquals(state, decoded);
    }

    @Test
    void defaultRejectsUnregisteredType() {
        // 安全默认：未登记类型不允许序列化（反序列化同理），挡住任意类反序列化攻击面
        FurySerializer serializer = FurySerializer.create();
        PlayerState state = new PlayerState(1L, "rogue", 1, new ArrayList<>());

        assertThrows(RuntimeException.class, () -> serializer.serialize(state));
    }

    @Test
    void startupRegistrationEnablesRoundTrip() {
        // 启动期（首次 serialize 之前）登记后即可正常收发；Fory 在首次收发后冻结注册
        FurySerializer serializer = FurySerializer.create().register(PlayerState.class);
        PlayerState state = new PlayerState(1L, "rogue", 1, new ArrayList<>(List.of(3, 4)));

        byte[] payload = serializer.serialize(state);
        assertEquals(state, serializer.deserialize(payload, PlayerState.class));
    }

    public static final class PlayerState {
        public long playerId;
        public String name;
        public int level;
        public List<Integer> inventory;

        public PlayerState() {
        }

        PlayerState(long playerId, String name, int level, List<Integer> inventory) {
            this.playerId = playerId;
            this.name = name;
            this.level = level;
            this.inventory = inventory;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PlayerState that)) {
                return false;
            }
            return playerId == that.playerId
                && level == that.level
                && Objects.equals(name, that.name)
                && Objects.equals(inventory, that.inventory);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, name, level, inventory);
        }
    }
}
