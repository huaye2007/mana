package cn.managame.ai.internal;

import cn.managame.ai.AiToolContext;
import cn.managame.ai.AiToolDefinition;
import cn.managame.ai.annotation.AiTool;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Compiled tool invocation target, backed either by a {@link MethodHandle} for an {@link AiTool}-annotated method or
 * by a programmatic handler. Internal implementation, not public API.
 */
public final class ToolMethod {
    private final AiToolDefinition definition;
    private final MethodHandle handle;
    private final BiConsumer<AiToolContext, String> programmatic;
    private final boolean returnsVoid;
    private final boolean rawStringArgument;

    private ToolMethod(AiToolDefinition definition, MethodHandle handle, BiConsumer<AiToolContext, String> programmatic,
                       boolean returnsVoid, boolean rawStringArgument) {
        this.definition = definition;
        this.handle = handle;
        this.programmatic = programmatic;
        this.returnsVoid = returnsVoid;
        this.rawStringArgument = rawStringArgument;
    }

    public static List<ToolMethod> scan(Object target) {
        Objects.requireNonNull(target, "target");
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(AiTool.class)) continue;
                if (seen.add(method.getName() + List.of(method.getParameterTypes()))) methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        List<ToolMethod> compiled = new ArrayList<>(methods.size());
        for (Method method : methods) {
            compiled.add(compile(target, method, method.getAnnotation(AiTool.class)));
        }
        return compiled;
    }

    public static ToolMethod programmatic(String name, String description, String group, boolean readOnly,
                                          BiConsumer<AiToolContext, String> handler) {
        Objects.requireNonNull(handler, "handler");
        AiToolDefinition def = new AiToolDefinition(name, description, group, readOnly, null, String.class);
        return new ToolMethod(def, null, handler, true, true);
    }

    private static ToolMethod compile(Object target, Method method, AiTool annotation) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw invalid(method, "must not be static");
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        Type[] genericTypes = method.getGenericParameterTypes();
        if (paramTypes.length > 2) {
            throw invalid(method, "must take (), (arg), (AiToolContext) or (AiToolContext, arg)");
        }
        boolean hasContext;
        Type argumentType;
        if (paramTypes.length == 0) {
            hasContext = false;
            argumentType = null;
        } else if (paramTypes.length == 1) {
            if (AiToolContext.class.isAssignableFrom(paramTypes[0])) {
                hasContext = true;
                argumentType = null;
            } else {
                hasContext = false;
                argumentType = genericTypes[0];
            }
        } else {
            if (!AiToolContext.class.isAssignableFrom(paramTypes[0])) {
                throw invalid(method, "must take AiToolContext as its first of two parameters");
            }
            if (AiToolContext.class.isAssignableFrom(paramTypes[1])) {
                throw invalid(method, "must not take AiToolContext twice");
            }
            hasContext = true;
            argumentType = genericTypes[1];
        }

        boolean returnsVoid = method.getReturnType() == void.class;
        if (returnsVoid && !hasContext) {
            throw invalid(method, "returns void without taking AiToolContext to answer later");
        }

        String toolName = annotation.name().isBlank() ? method.getName() : annotation.name().trim();

        MethodHandle handle;
        try {
            method.setAccessible(true);
            handle = MethodHandles.lookup().unreflect(method).bindTo(target);
            if (!hasContext && argumentType == null) {
                handle = MethodHandles.dropArguments(handle, 0, AiToolContext.class, Object.class);
            } else if (hasContext && argumentType == null) {
                handle = MethodHandles.dropArguments(handle, 1, Object.class);
            } else if (!hasContext) {
                handle = MethodHandles.dropArguments(handle, 0, AiToolContext.class);
            }
            handle = handle.asType(MethodType.methodType(Object.class, AiToolContext.class, Object.class));
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot compile @AiTool method " + describe(method), e);
        }

        AiToolDefinition def = new AiToolDefinition(
                toolName, annotation.description(), annotation.group(), annotation.readOnly(),
                method, argumentType);
        return new ToolMethod(def, handle, null, returnsVoid, argumentType == String.class);
    }

    public AiToolDefinition definition() { return definition; }
    public boolean returnsVoid() { return returnsVoid; }
    public boolean isRawStringArgument() { return rawStringArgument; }
    public boolean hasArgument() { return definition.argumentType() != null; }

    public Object invoke(AiToolContext context, Object argument) throws Throwable {
        if (programmatic != null) {
            programmatic.accept(context, (String) argument);
            return null;
        }
        return handle.invokeExact(context, argument);
    }

    private static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException("@AiTool method " + describe(method) + " " + reason);
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }
}
