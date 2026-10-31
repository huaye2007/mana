package cn.managame.data.spi;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

/** One saved field of an entity or log, read and written through method handles prepared once. */
public final class EntityField {
    private final Field field;
    private final Type valueType;
    private final MethodHandle getter, setter;

    /** @param newInstance an instance made by the class's no-argument constructor */
    EntityField(Field field, Object newInstance) {
        this.field = field;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            getter = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
            setter = lookup.unreflectSetter(field).asType(MethodType.methodType(void.class, Object.class, Object.class));
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("cannot access " + field, e);
        }
        Object initialValue = get(newInstance);
        valueType = initialValue == null ? field.getGenericType() : withClass(field.getGenericType(), initialValue.getClass());
    }

    /** The Java field, for its annotations. */
    public Field field() { return field; }

    public String name() { return field.getName(); }

    public Class<?> type() { return field.getType(); }

    /**
     * The field's type with the class of the value the no-argument constructor gives it, such as
     * {@code ConcurrentHashMap<Integer, Long>} for {@code Map<Integer, Long> items = new ConcurrentHashMap<>()}: what a
     * load decodes the value into, so it is the same kind of collection again.
     */
    public Type valueType() { return valueType; }

    public Object get(Object entity) {
        try {
            return (Object) getter.invokeExact(entity);
        } catch (Throwable e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }

    /** Sets the field; IllegalArgumentException for null in a primitive field. */
    public void set(Object entity, Object value) {
        if (value == null && field.getType().isPrimitive()) throw new IllegalArgumentException("null for primitive field " + field);
        try {
            setter.invokeExact(entity, value);
        } catch (Throwable e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /** {@code type} with {@code valueClass} in place of its class, keeping its type arguments where they fit. */
    private static Type withClass(Type type, Class<?> valueClass) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == valueClass) return type;
            Type[] arguments = parameterized.getActualTypeArguments();
            if (valueClass.getTypeParameters().length != arguments.length) return type;
            return new SimpleParameterizedType(valueClass, arguments);
        }
        return valueClass.getTypeParameters().length == 0 ? valueClass : type;
    }

    private record SimpleParameterizedType(Class<?> rawType, Type[] arguments) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return rawType.getDeclaringClass(); }

        @Override public boolean equals(Object other) {
            return other instanceof ParameterizedType type && rawType.equals(type.getRawType())
                    && Objects.equals(getOwnerType(), type.getOwnerType())
                    && Arrays.equals(arguments, type.getActualTypeArguments());
        }

        @Override public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(getOwnerType()) ^ rawType.hashCode();
        }

        @Override public String toString() {
            StringBuilder name = new StringBuilder(rawType.getName()).append('<');
            for (int i = 0; i < arguments.length; i++) name.append(i == 0 ? "" : ", ").append(arguments[i].getTypeName());
            return name.append('>').toString();
        }
    }
}
