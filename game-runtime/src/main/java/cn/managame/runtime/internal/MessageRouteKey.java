package cn.managame.runtime.internal;

import cn.managame.runtime.route.RouteKeyExtractor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Compiles message member access once; reflection is never used during extraction. */
public final class MessageRouteKey {
    private MessageRouteKey() {}

    public static <T> RouteKeyExtractor<T> field(Class<T> type, String name) {
        validateName(type, name);
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                validateType(field.getType(), field.getModifiers(), field.toString());
                return extractor(MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectGetter(field));
            } catch (NoSuchFieldException ignored) {
                // The nearest declaration wins, including private inherited fields.
            } catch (IllegalAccessException failure) {
                throw new IllegalArgumentException("Cannot access RouteKey field: " + name, failure);
            }
        }
        throw new IllegalArgumentException("Missing RouteKey field: " + type.getName() + "." + name);
    }

    public static <T> RouteKeyExtractor<T> method(Class<T> type, String name) {
        validateName(type, name);
        try {
            Method method = type.getMethod(name);
            validateType(method.getReturnType(), method.getModifiers(), method.toString());
            if (method.isVarArgs()) throw new IllegalArgumentException("RouteKey method must have no arguments: " + method);
            return extractor(MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup()).unreflect(method));
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalArgumentException("Expected accessible public no-argument RouteKey method: " + name, failure);
        }
    }

    private static void validateName(Class<?> type, String name) {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("RouteKey member name must not be blank");
    }

    private static void validateType(Class<?> type, int modifiers, String member) {
        if (Modifier.isStatic(modifiers) || !(type == byte.class || type == Byte.class
                || type == short.class || type == Short.class || type == int.class || type == Integer.class
                || type == long.class || type == Long.class)) {
            throw new IllegalArgumentException("RouteKey requires an instance integral member: " + member);
        }
    }

    private static <T> RouteKeyExtractor<T> extractor(MethodHandle source) {
        MethodHandle handle = source.asType(MethodType.methodType(long.class, Object.class));
        return message -> {
            try { return (long) handle.invokeExact((Object) message); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("RouteKey method failed", failure); }
        };
    }
}
