package cn.managame.runtime;

import cn.managame.runtime.annotation.RouteKey;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;

/** Common RouteKeyResolvers. */
public final class RouteKeys {
    private RouteKeys() {}

    /** The source's business id, for example the acting player's id in a player domain. */
    public static RouteKeyResolver businessId() { return (source, message) -> source.businessId(); }

    /** One key for every message, for a domain that runs everything on one route. */
    public static RouteKeyResolver constant(long key) { return (source, message) -> key; }

    /**
     * The message's field or no-argument method annotated {@code @RouteKey} for {@code domain}, of an integral
     * type. Each message class is looked up once; a class without one cannot be routed in this domain.
     */
    public static RouteKeyResolver annotated(int domain) {
        ClassValue<Optional<MethodHandle>> accessors = new ClassValue<>() {
            @Override protected Optional<MethodHandle> computeValue(Class<?> type) { return Optional.ofNullable(accessor(type, domain)); }
        };
        return (source, message) -> {
            MethodHandle accessor = accessors.get(message.getClass()).orElseThrow(() ->
                    new IllegalArgumentException(message.getClass().getName() + " has no @RouteKey for domain " + domain));
            try {
                return (long) accessor.invokeExact(message);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("Cannot read the route key of " + message.getClass().getName(), e);
            }
        };
    }

    /** {@code (Object) long} reading the annotated member, or null when the class has none. */
    private static MethodHandle accessor(Class<?> type, int domain) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods())
                if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers()) && routes(method, domain))
                    return adapt(method, member -> MethodHandles.lookup().unreflect((Method) member));
            for (Field field : current.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers()) && routes(field, domain))
                    return adapt(field, member -> MethodHandles.lookup().unreflectGetter((Field) member));
        }
        return null;
    }

    private static boolean routes(AccessibleObject member, int domain) {
        RouteKey key = member.getAnnotation(RouteKey.class);
        return key != null && Arrays.stream(key.value()).anyMatch(d -> d == domain);
    }

    private interface Unreflect { MethodHandle apply(AccessibleObject member) throws IllegalAccessException; }

    private static MethodHandle adapt(AccessibleObject member, Unreflect unreflect) {
        try {
            member.setAccessible(true);
            return unreflect.apply(member).asType(MethodType.methodType(long.class, Object.class));
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("@RouteKey must be on an integral field or method: " + member, e);
        }
    }
}
