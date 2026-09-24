package cn.managame.core.write;

import cn.managame.core.DataException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** One accumulation of live entity operations. Its owner supplies synchronization. */
final class WriteBuffer {
    private final Map<Object, WriteOperation<?>> operations = new LinkedHashMap<>();
    // Whole-group deletion seals the preceding ID map; reduction must not cross that boundary.
    private final List<WriteOperation<?>> beforeGroupDelete = new ArrayList<>();
    private long submissions;

    int size() { return beforeGroupDelete.size() + operations.size(); }
    long submissions() { return submissions; }
    boolean canAdd(WriteOperation<?> operation, int capacity) {
        return size() < capacity || (operation.type() != WriteType.DELETE_GROUP
                && operations.containsKey(operation.id()));
    }

    void add(WriteOperation<?> operation) {
        if (operation.type() == WriteType.DELETE_GROUP) {
            beforeGroupDelete.addAll(operations.values());
            beforeGroupDelete.add(operation);
            operations.clear();
        } else {
            WriteOperation<?> previous = operations.get(operation.id());
            WriteOperation<?> merged = previous == null ? operation : merge(previous, operation);
            if (merged == null) operations.remove(operation.id());
            else operations.put(operation.id(), merged);
        }
        submissions++;
    }

    private static WriteOperation<?> merge(WriteOperation<?> previous, WriteOperation<?> next) {
        return switch (previous.type()) {
            case INSERT -> switch (next.type()) {
                case UPDATE -> next.withType(WriteType.INSERT);
                case DELETE -> null;
                default -> throw invalid(previous, next);
            };
            case UPDATE -> switch (next.type()) {
                case UPDATE, DELETE -> next;
                default -> throw invalid(previous, next);
            };
            case DELETE -> switch (next.type()) {
                case INSERT -> next.withType(WriteType.DELETE_INSERT);
                case DELETE -> previous;
                default -> throw invalid(previous, next);
            };
            case DELETE_INSERT -> switch (next.type()) {
                case UPDATE -> next.withType(WriteType.DELETE_INSERT);
                case DELETE -> next;
                default -> throw invalid(previous, next);
            };
            case DELETE_GROUP -> throw invalid(previous, next);
        };
    }

    private static DataException invalid(WriteOperation<?> previous, WriteOperation<?> next) {
        return new DataException("Invalid buffered write sequence: " + previous.type() + " -> "
                + next.type() + ", entity=" + next.metadata().type().getName() + ", id=" + next.id());
    }

    void forEach(Consumer<WriteOperation<?>> consumer) {
        beforeGroupDelete.forEach(consumer);
        operations.values().forEach(consumer);
    }

    void clear() {
        operations.clear();
        beforeGroupDelete.clear();
        submissions = 0;
    }
}