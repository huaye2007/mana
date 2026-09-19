package cn.managame.core.mapping;

public interface ValueConverter {
    /** Validates a declared scalar/binary type without reading entity values or doing I/O. */
    default void validate(Class<?> type) { }

    Object toStore(Object value, Class<?> declaredType);
    Object fromStore(Object value, Class<?> targetType);
}
