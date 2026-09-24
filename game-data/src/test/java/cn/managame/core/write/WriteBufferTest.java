package cn.managame.core.write;

import cn.managame.core.DataException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteBufferTest {
    private static WriteOperation<Row> operation(WriteType type) {
        return switch (type) {
            case INSERT -> insert(1);
            case UPDATE -> WriteOperation.update(METADATA, new Row(1), 1L, 1L);
            case DELETE -> WriteOperation.delete(METADATA, 1L, 1L);
            case DELETE_INSERT -> insert(1).withType(type);
            default -> throw new AssertionError(type);
        };
    }
    private static List<WriteOperation<?>> contents(WriteBuffer buffer) {
        var result = new ArrayList<WriteOperation<?>>();
        buffer.forEach(result::add);
        return result;
    }
    private static WriteBuffer startingWith(WriteType type) {
        var buffer = new WriteBuffer();
        if (type == WriteType.DELETE_INSERT) buffer.add(operation(WriteType.DELETE));
        buffer.add(operation(type == WriteType.DELETE_INSERT ? WriteType.INSERT : type));
        return buffer;
    }

    @Test void transitionsPreserveTheRequiredDatabaseAction() {
        record Transition(WriteType previous, WriteType next, WriteType expected) { }
        var transitions = List.of(
                new Transition(WriteType.INSERT, WriteType.UPDATE, WriteType.INSERT),
                new Transition(WriteType.INSERT, WriteType.DELETE, null),
                new Transition(WriteType.UPDATE, WriteType.UPDATE, WriteType.UPDATE),
                new Transition(WriteType.UPDATE, WriteType.DELETE, WriteType.DELETE),
                new Transition(WriteType.DELETE, WriteType.INSERT, WriteType.DELETE_INSERT),
                new Transition(WriteType.DELETE, WriteType.DELETE, WriteType.DELETE),
                new Transition(WriteType.DELETE_INSERT, WriteType.UPDATE, WriteType.DELETE_INSERT),
                new Transition(WriteType.DELETE_INSERT, WriteType.DELETE, WriteType.DELETE));
        for (var transition : transitions) {
            var buffer = startingWith(transition.previous());
            var next = operation(transition.next());
            buffer.add(next);
            var result = contents(buffer);
            if (transition.expected() == null) assertTrue(result.isEmpty(), transition.toString());
            else {
                assertEquals(transition.expected(), result.getFirst().type(), transition.toString());
                if (transition.next() == WriteType.UPDATE || transition.next() == WriteType.INSERT) {
                    assertSame(next.entity(), result.getFirst().entity(), "must retain the latest live reference");
                }
            }
        }
    }

    @Test void invalidSequencesAreRejectedWithoutChangingPreviouslyAcceptedWork() {
        for (WriteType previous : List.of(WriteType.INSERT, WriteType.UPDATE, WriteType.DELETE_INSERT)) {
            var buffer = startingWith(previous);
            var before = contents(buffer);
            long submissions = buffer.submissions();
            assertThrows(DataException.class, () -> buffer.add(insert(1)));
            assertEquals(before, contents(buffer));
            assertEquals(submissions, buffer.submissions());
        }
        var buffer = startingWith(WriteType.DELETE);
        assertThrows(DataException.class, () -> buffer.add(operation(WriteType.UPDATE)));
        assertEquals(WriteType.DELETE, contents(buffer).getFirst().type());
    }

    @Test void groupDeletionSeparatesIdentityMapsAndDoubleBuffersRemainIndependent() {
        var pair = new DoubleWriteBuffer();
        var first = pair.active();
        first.add(insert(1));
        first.add(WriteOperation.deleteGroup(METADATA, 1L));
        first.add(insert(1));
        assertEquals(List.of(WriteType.INSERT, WriteType.DELETE_GROUP, WriteType.INSERT),
                contents(first).stream().map(WriteOperation::type).toList());
        assertSame(first, pair.swap());
        pair.active().add(operation(WriteType.DELETE));
        assertThrows(IllegalStateException.class, pair::swap);
        first.clear();
        var second = pair.swap();
        assertSame(first, pair.active());
        assertEquals(List.of(WriteType.DELETE), contents(second).stream().map(WriteOperation::type).toList());
        assertTrue(contents(first).isEmpty());
    }
}