package cn.managame.common.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataTest {

    @Test
    void factoriesCreateExplicitlyTypedValues() {
        Metadata text = Metadata.ofString((short) 1, "node-a");
        Metadata number = Metadata.ofLong((short) 2, 42L);

        assertEquals(Metadata.TYPE_STRING, text.getType());
        assertEquals("node-a", text.getStrVal());
        assertEquals(Metadata.TYPE_LONG, number.getType());
        assertEquals(42L, number.getVal());
        assertEquals("", Metadata.ofString((short) 3, null).getStrVal());
    }

    @Test
    void mutableApiSwitchesTheActiveValueType() {
        Metadata metadata = new Metadata();
        metadata.setKey((short) 7);
        metadata.setStrVal("gateway");

        assertEquals(Metadata.TYPE_STRING, metadata.getType());
        assertEquals("gateway", metadata.getStrVal());

        metadata.setVal(9L);

        assertEquals(Metadata.TYPE_LONG, metadata.getType());
        assertEquals(9L, metadata.getVal());
        assertNull(metadata.getStrVal());
    }

    @Test
    void findHelpersRespectKeyAndType() {
        Metadata[] metadata = {
                null,
                Metadata.ofString((short) 10, "zone-1"),
                Metadata.ofLong((short) 11, 100L)
        };

        assertEquals("zone-1", Metadata.findString(metadata, (short) 10));
        assertNull(Metadata.findString(metadata, (short) 11));
        assertEquals(100L, Metadata.findLong(metadata, (short) 11, -1L));
        assertEquals(-1L, Metadata.findLong(metadata, (short) 10, -1L));
    }
}
