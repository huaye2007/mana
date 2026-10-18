package cn.managame.runtime.internal;

import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.timer.Cron;
import io.netty.handler.codec.http.HttpObject;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared annotation-scanning rules of the Runtime compilers. */
final class Reflection {
    private Reflection() {}

    /**
     * Public methods of {@code target}. Annotated non-public or static methods anywhere in the class
     * hierarchy are rejected instead of silently ignored.
     */
    static Set<Method> methods(Object target) {
        for (Class<?> type = target.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(HandlerMethod.class)) requireHandlerMethod(m);
                else if (m.isAnnotationPresent(EventMethod.class) || m.isAnnotationPresent(Cron.class)) requireVoidMethod(m);
                else if (m.isAnnotationPresent(HttpMethod.class)) requireHttpMethod(m);
            }
        }
        return new LinkedHashSet<>(Arrays.asList(target.getClass().getMethods()));
    }

    static void requireVoidMethod(Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class
                || m.isVarArgs())
            throw invalid("Expected public instance void method: " + m);
    }

    /** A Handler method returns void, or the response type its request registered; never a primitive. */
    static void requireHandlerMethod(Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.isVarArgs()
                || (m.getReturnType().isPrimitive() && m.getReturnType() != void.class))
            throw invalid("Expected public instance Handler method returning void or a response: " + m);
    }

    static void requireHttpMethod(Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.isVarArgs()
                || (m.getReturnType().isPrimitive() && m.getReturnType() != void.class)
                || HttpObject.class.isAssignableFrom(m.getReturnType()))
            throw invalid("Expected public instance HTTP method returning void or a business object: " + m);
    }

    /** A RouteKey rule names at most one of a field and a method, and neither may be blank. */
    static void requireKeyRule(String field, String method, String owner) {
        if ((!field.isEmpty() && field.isBlank()) || (!method.isEmpty() && method.isBlank())
                || (!field.isEmpty() && !method.isEmpty()))
            throw invalid(owner + " RouteKey requires one field or method");
    }

    static MethodHandle bind(Object target, Method method) {
        try {
            return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup()).unreflect(method)
                    .bindTo(target);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access " + method, e);
        }
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
