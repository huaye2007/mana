package com.github.huaye2007.mana.serialization;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SerializerManagerTest {

    @Test
    void defaultSingletonHasAllBuiltInSerializers() {
        SerializerManager manager = SerializerManager.getInstance();
        for (SerializationType type : SerializationType.values()) {
            ISerializer serializer = manager.getISerializer(type.typeId());
            assertNotNull(serializer, "missing default serializer for " + type);
            assertSame(type, serializer.type());
        }
    }

    @Test
    void unknownSerialTypeResolvesToNull() {
        assertNull(SerializerManager.getInstance().getISerializer((byte) 0));
        assertNull(SerializerManager.getInstance().getISerializer((byte) 99));
    }

    @Test
    void registerOverridesByType() {
        SerializerManager manager = new SerializerManager();
        ISerializer custom = new ISerializer() {
            @Override
            public SerializationType type() {
                return SerializationType.JSON;
            }

            @Override
            public <T> byte[] serialize(T value) {
                return new byte[0];
            }

            @Override
            public <T> T deserialize(byte[] array, Class<T> type) {
                return null;
            }
        };
        manager.register(custom);
        assertSame(custom, manager.getISerializer(SerializationType.JSON.typeId()));
    }
}
