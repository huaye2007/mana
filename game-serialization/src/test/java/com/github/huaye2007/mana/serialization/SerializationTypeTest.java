package com.github.huaye2007.mana.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SerializationTypeTest {

    @Test
    void resolvesKnownTypeIds() {
        for (SerializationType type : SerializationType.values()) {
            assertSame(type, SerializationType.getSerializationType(type.typeId()));
        }
    }

    @Test
    void returnsNullForUnknownTypeId() {
        assertNull(SerializationType.getSerializationType((byte) 0));
        assertNull(SerializationType.getSerializationType((byte) 99));
        assertNull(SerializationType.getSerializationType((byte) -1));
    }

    @Test
    void typeIdsAreStableWireContract() {
        assertEquals(1, SerializationType.JSON.typeId());
        assertEquals(2, SerializationType.PROTOBUF.typeId());
        assertEquals(3, SerializationType.FORY.typeId());
    }
}
