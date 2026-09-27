package cn.managame.data.meta;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

/** Compiled field access for mapper implementations; reflection is confined to initialization. */
public final class FieldAccessor {
    private final Field field;
    private final VarHandle handle;
    public FieldAccessor(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
            throw new IllegalArgumentException("Mapped field must be mutable and non-static: " + field);
        this.field = field;
        try {
            handle = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectVarHandle(field);
        } catch (IllegalAccessException e) { throw new IllegalArgumentException("Cannot access " + field, e); }
    }
    public Object get(Object entity) { return handle.get(entity); }
    public void set(Object entity, Object value) { handle.set(entity, value); }
    public Field field() { return field; }
    public Class<?> type() { return field.getType(); }
    public Type genericType() { return field.getGenericType(); }
}
