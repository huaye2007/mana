package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameTaskContextMetadataTest {

    @Test
    void metadataIsDefensivelyCopiedAtEveryBoundary() {
        Metadata original = Metadata.ofLong((short) 1, 10L);
        GameTaskContext context = new GameTaskContext(GameTaskType.COMMAND,
                (byte) 1, 1L, (byte) 0, 0L, new Metadata[]{original});

        original.setVal(20L);
        assertEquals(10L, context.getMetadata((short) 1).getVal());

        Metadata[] exposed = context.getMetadatas();
        exposed[0].setVal(30L);
        assertEquals(10L, context.getMetadata((short) 1).getVal());

        Metadata single = context.getMetadata((short) 1);
        single.setVal(40L);
        assertEquals(10L, context.getMetadata((short) 1).getVal());
    }

    @Test
    void eventMetadataCannotBeMutatedThroughContextApi() {
        GameEventTaskContext context = new GameEventTaskContext(GameTaskType.EVENT,
                (byte) 1, 1L, (byte) 0, 0L, null);
        assertThrows(UnsupportedOperationException.class,
                () -> context.addMetadataLongVal((short) 1, 1L));
    }
}
