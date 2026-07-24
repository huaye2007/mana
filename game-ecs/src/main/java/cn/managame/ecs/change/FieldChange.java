package cn.managame.ecs.change;

import java.util.Objects;

/**
 * Old and new values of one business field.
 *
 * <p>Values may be {@code null}. For mutable collections, callers should pass
 * immutable snapshots when manually marking a field as changed.</p>
 *
 * @param fieldName stable business or protocol field name
 * @param previousValue value before the change
 * @param currentValue value after the change
 */
public record FieldChange(
        String fieldName,
        Object previousValue,
        Object currentValue) {

    public FieldChange {
        Objects.requireNonNull(fieldName, "fieldName");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
    }
}
