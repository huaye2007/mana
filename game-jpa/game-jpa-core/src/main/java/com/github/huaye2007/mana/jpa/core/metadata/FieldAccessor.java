package com.github.huaye2007.mana.jpa.core.metadata;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Field accessor backed by MethodHandle getter and setter calls.
 * <p>
 * Accessors are created during metadata resolution, avoiding repeated reflective
 * access checks on hot read/write paths.
 */
public class FieldAccessor {

    private final Field field;
    private final MethodHandle getter;
    private final MethodHandle setter;

    public FieldAccessor(Field field) {
        this.field = field;
        field.setAccessible(true);
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            this.getter = lookup.unreflectGetter(field);
            this.setter = lookup.unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot create accessor for field: " + field, e);
        }
    }

    public Object get(Object instance) {
        try {
            return getter.invoke(instance);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot read field: " + field.getName(), e);
        }
    }

    public void set(Object instance, Object value) {
        try {
            setter.invoke(instance, value);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot write field: " + field.getName(), e);
        }
    }

    public Field field() {
        return field;
    }
}
