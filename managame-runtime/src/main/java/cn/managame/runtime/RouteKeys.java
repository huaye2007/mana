package cn.managame.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Common RouteKeyResolvers. */
public final class RouteKeys {
    private RouteKeys() {}

    /**
     * The source's business id: the acting player's id, from the session for a request or from the publishing task for
     * an event. The default of a domain registered without a resolver.
     */
    public static RouteKeyResolver businessId() { return (source, message) -> source.businessId(); }

    /** One key for every message, for a domain that runs everything on one route. */
    public static RouteKeyResolver constant(long key) { return (source, message) -> key; }

    /**
     * The message's member named {@code name}, such as {@code "guildId"}: a record component, a no-argument method or
     * a field, of an integral type or its box, declared by the message's class or a superclass. Each message class is
     * looked up once. Registering a method whose message has no such member throws IllegalArgumentException, so a
     * misspelt or missing name fails at startup.
     */
    public static RouteKeyResolver field(String name) {
        Objects.requireNonNull(name, "name");
        ClassValue<MethodHandle> accessors = new ClassValue<>() {
            @Override protected MethodHandle computeValue(Class<?> type) { return accessor(type, name); }
        };
        return new RouteKeyResolver() {
            @Override public long routeKey(Context source, Object message) {
                try {
                    return (long) accessors.get(message.getClass()).invokeExact(message);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException("Cannot read " + name + " of " + message.getClass().getName(), e);
                }
            }

            @Override public void check(Class<?> messageType) { accessors.get(messageType); }
        };
    }

    /** {@code (Object) long} reading the member named {@code name}; IllegalArgumentException when there is none. */
    private static MethodHandle accessor(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods())
                if (method.getName().equals(name) && method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers()))
                    return adapt(type, method, member -> MethodHandles.lookup().unreflect((Method) member));
            for (Field field : current.getDeclaredFields())
                if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers()))
                    return adapt(type, field, member -> MethodHandles.lookup().unreflectGetter((Field) member));
        }
        throw new IllegalArgumentException(type.getName() + " has no " + name + " to route by");
    }

    private interface Unreflect { MethodHandle apply(AccessibleObject member) throws IllegalAccessException; }

    private static MethodHandle adapt(Class<?> type, AccessibleObject member, Unreflect unreflect) {
        try {
            member.setAccessible(true);
            return unreflect.apply(member).asType(MethodType.methodType(long.class, Object.class));
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException(type.getName() + " cannot be routed by " + member + ": it must be integral", e);
        }
    }
}
