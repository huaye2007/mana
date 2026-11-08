package cn.managame.runtime.annotation;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.Route;
import cn.managame.runtime.TaskKind;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A handler method found by its annotation and compiled once, at registration, into a method handle, so calling
 * it costs no reflection. It takes the message, or a context and the message, and runs in the domain of its
 * {@link Domain}, on the method or else on the class, on the route key its annotation's routeKey member of the
 * message holds, or else the domain's resolver gives. Registries such as the EventBus and the Dispatcher build on it.
 */
public final class AnnotatedMethod {
    private final Object target;
    private final Method method;
    private final int domain;
    private final Class<?> contextType, messageType;
    private final MethodHandle handle;

    /** Reads the route key from the message, {@code (Object) long}; null to route by the domain's resolver. */
    private final MethodHandle routeKey;
    private final TaskKind kind;

    private AnnotatedMethod(Object target, Method method, int domain, Class<?> contextType, Class<?> messageType, MethodHandle handle,
            MethodHandle routeKey, TaskKind kind) {
        this.kind = kind;
        this.target = target;
        this.method = method;
        this.domain = domain;
        this.contextType = contextType;
        this.messageType = messageType;
        this.handle = handle;
        this.routeKey = routeKey;
    }

    /**
     * The methods of {@code target} and its superclasses carrying {@code annotation}, in name order; a method
     * overridden in a subclass counts once. {@code routeKey} gives the annotation's routeKey, the message member to
     * route by, or empty for the domain's resolver. Throws IllegalArgumentException for a static method, a method
     * taking other parameters, one without a domain, or one whose message lacks its routeKey member. Each counts as its
     * own {@link TaskKind} of {@code runtime}.
     */
    public static <A extends Annotation> List<AnnotatedMethod> scan(GameRuntime runtime, Object target, Class<A> annotation, Function<? super A, String> routeKey) {
        List<AnnotatedMethod> found = new ArrayList<>();
        for (Method method : methods(target, annotation)) found.add(compile(runtime, target, method, routeKey.apply(method.getAnnotation(annotation))));
        return List.copyOf(found);
    }

    /**
     * The methods of {@code target} and its superclasses carrying {@code annotation}, in name order, unchecked; a
     * method overridden in a subclass counts once. For entries whose methods take no message, such as cron jobs.
     */
    public static List<Method> methods(Object target, Class<? extends Annotation> annotation) {
        Objects.requireNonNull(target, "target");
        List<Method> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(annotation)) continue;
                if (seen.add(method.getName() + List.of(method.getParameterTypes()))) found.add(method);
            }
        }
        found.sort(Comparator.comparing(Method::getName));
        return found;
    }

    private static AnnotatedMethod compile(GameRuntime runtime, Object target, Method method, String routeKey) {
        if (Modifier.isStatic(method.getModifiers())) throw invalid(method, "must not be static");
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length == 0 || parameters.length > 2) throw invalid(method, "must take (message) or (context, message)");
        if (parameters.length == 2 && !Context.class.isAssignableFrom(parameters[0]))
            throw invalid(method, "must take a Context as its first of two parameters");
        Domain domain = method.getAnnotation(Domain.class);
        if (domain == null) domain = target.getClass().getAnnotation(Domain.class);
        if (domain == null) throw invalid(method, "needs @Domain on the method or its class");
        Class<?> messageType = parameters[parameters.length - 1];
        MethodHandle routeKeyReader = routeKey.isEmpty() ? null : routeKeyReader(method, messageType, routeKey);
        try {
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(target);
            if (parameters.length == 1) handle = MethodHandles.dropArguments(handle, 0, Context.class);
            handle = handle.asType(MethodType.methodType(Object.class, Context.class, Object.class));
            return new AnnotatedMethod(target, method, domain.value(), parameters.length == 2 ? parameters[0] : Context.class,
                    messageType, handle, routeKeyReader, runtime.taskKind(describe(method)));
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot use " + describe(method), e);
        }
    }

    /**
     * {@code (Object) long} reading {@code name} from a {@code type}: a no-argument method of that name, else its getter
     * {@code getName()}, else a field, declared by the type or a superclass.
     */
    private static MethodHandle routeKeyReader(Method handler, Class<?> type, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method found = null;
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                if (method.getName().equals(name)) found = method;
                else if (found == null && method.getName().equals(getter)) found = method;
            }
            try {
                if (found != null) {
                    found.setAccessible(true);
                    return routeKeyType(MethodHandles.lookup().unreflect(found));
                }
                for (Field field : current.getDeclaredFields()) {
                    if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        return routeKeyType(MethodHandles.lookup().unreflectGetter(field));
                    }
                }
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalArgumentException(describe(handler) + " cannot route by " + type.getName() + "." + name
                        + ": it must be an integral type or its box", e);
            }
        }
        throw invalid(handler, "routes by " + name + ", which " + type.getName() + " has neither as a method, getter nor field");
    }

    private static MethodHandle routeKeyType(MethodHandle reader) { return reader.asType(MethodType.methodType(long.class, Object.class)); }

    private static IllegalArgumentException invalid(Method method, String problem) {
        return new IllegalArgumentException(describe(method) + " " + problem);
    }

    private static String describe(Method method) { return method.getDeclaringClass().getName() + "#" + method.getName(); }

    public int domain() { return domain; }

    /** What the tasks of this method count as. */
    public TaskKind kind() { return kind; }

    /**
     * Where {@code message}, arriving from {@code source}, runs: in the method's domain, on the key its routeKey member
     * holds, or else on the one the domain's resolver gives, acting for the source. An exception, such as a null boxed
     * key, means the message cannot be routed.
     */
    public Route route(GameRuntime runtime, Context source, Object message) {
        if (routeKey == null) return runtime.route(domain, source, message);
        long key;
        try {
            key = (long) routeKey.invokeExact(message);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
        return Route.of(domain, key, source.businessType(), source.businessId());
    }

    /** The class of message this method handles. */
    public Class<?> messageType() { return messageType; }

    public Method method() { return method; }

    /** Whether it returns void: the one kind of method that answers later through its context, not with its result. */
    public boolean returnsVoid() { return method.getReturnType() == void.class; }

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
