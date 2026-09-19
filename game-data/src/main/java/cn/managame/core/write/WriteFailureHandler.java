package cn.managame.core.write;

import cn.managame.core.metadata.PropertyMetadata;

import java.util.List;

/** Called synchronously when a table batch reaches an unrecoverable write failure. */
@FunctionalInterface
public interface WriteFailureHandler {
    void onFailure(List<WriteOperation<?>> operations, Throwable error);

    static WriteFailureHandler stderr() {
        return (ops, error) -> {
            System.err.println("[game-data] unrecoverable write failure, operations=" + ops.size());
            for (WriteOperation<?> op : ops) {
                StringBuilder line = new StringBuilder("  entity=")
                        .append(op.metadata().type().getName())
                        .append(", type=").append(op.type())
                        .append(", id=").append(op.id());
                if (op.groupKey() != null) line.append(", groupKey=").append(op.groupKey());
                if (op.entity() != null) {
                    line.append(", data={");
                    boolean first = true;
                    for (PropertyMetadata property : op.metadata().properties()) {
                        if (!first) line.append(", ");
                        first = false;
                        line.append(property.propertyName()).append('=').append(property.get(op.entity()));
                    }
                    line.append('}');
                }
                System.err.println(line);
            }
            error.printStackTrace(System.err);
        };
    }
}
