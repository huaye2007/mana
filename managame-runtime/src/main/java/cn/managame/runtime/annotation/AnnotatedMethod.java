package cn.managame.runtime.annotation;

import cn.managame.runtime.Context;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A handler method found by its annotation and compiled once, at registration, into a method handle, so calling
 * it costs no reflection. It takes the message, or a context and the message, and runs in the domain of its
 * {@link Domain}, on the method or else on the class. Registries such as the EventBus and the Dispatcher build on
 * it.
 */
public final class AnnotatedMethod {
    private final Object target;
    private final Method method;
    private final int domain;
    private final Class<?> contextType, messageType;
    private final MethodHandle handle;

    private AnnotatedMethod(Object target, Method method, int domain, Class<?> contextType, Class<?> messageType, MethodHandle handle) {
        this.target = target;
        this.method = method;
        this.domain = domain;
        this.contextType = contextType;
        this.messageType = messageType;
        this.handle = handle;
    }

    /**
     * The methods of {@code target} and its superclasses carrying {@code annotation}, in name order; a method
     * overridden in a subclass counts once. Throws IllegalArgumentException for a static method, a method taking
     * other parameters, or one without a domain.
     */
    public static List<AnnotatedMethod> scan(Object target, Class<? extends Annotation> annotation) {
        Objects.requireNonNull(target, "target");
        List<AnnotatedMethod> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(annotation)) continue;
                if (seen.add(method.getName() + List.of(method.getParameterTypes())))
                    found.add(compile(target, method));
            }
        }
        found.sort(Comparator.comparing(m -> m.method.getName()));
        return List.copyOf(found);
    }

    private static AnnotatedMethod compile(Object target, Method method) {
        if (Modifier.isStatic(method.getModifiers())) throw invalid(method, "must not be static");
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length == 0 || parameters.length > 2) throw invalid(method, "must take (message) or (context, message)");
        if (parameters.length == 2 && !Context.class.isAssignableFrom(parameters[0]))
            throw invalid(method, "must take a Context as its first of two parameters");
        Domain domain = method.getAnnotation(Domain.class);
        if (domain == null) domain = target.getClass().getAnnotation(Domain.class);
        if (domain == null) throw invalid(method, "needs @Domain on the method or its class");
        try {
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(target);
            if (parameters.length == 1) handle = MethodHandles.dropArguments(handle, 0, Context.class);
            handle = handle.asType(MethodType.methodType(Object.class, Context.class, Object.class));
            return new AnnotatedMethod(target, method, domain.value(), parameters.length == 2 ? parameters[0] : Context.class,
                    parameters[parameters.length - 1], handle);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot use " + describe(method), e);
        }
    }

    private static IllegalArgumentException invalid(Method method, String problem) {
        return new IllegalArgumentException(describe(method) + " " + problem);
    }

    private static String describe(Method method) { return method.getDeclaringClass().getName() + "#" + method.getName(); }

    public int domain() { return domain; }

    /** The class of message this method handles. */
    public Class<?> messageType() { return messageType; }

    public Method method() { return method; }

    public Object target() { return target; }

    /**
     * Checks at registration that the method can take the context a registry supplies, so a method declaring a
     * context of another source fails at startup rather than per call.
     */
    public void requireContext(Class<? extends Context> supplied) {
        if (!contextType.isAssignableFrom(supplied))
            throw invalid(method, "takes " + contextType.getSimpleName() + " but is given " + supplied.getSimpleName());
    }

    /** Calls the method; void returns null. */
    public Object invoke(Context context, Object message) throws Exception {
        try {
            return (Object) handle.invokeExact(context, message);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    @Override public String toString() { return describe(method); }
}
