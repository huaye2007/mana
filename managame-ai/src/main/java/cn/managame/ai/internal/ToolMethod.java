package cn.managame.ai.internal;

import cn.managame.ai.AiCodec;
import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.mcp.McpToolContext;
import cn.managame.ai.mcp.McpToolDefinition;
import cn.managame.ai.mcp.McpToolResult;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One {@link McpTool}-annotated Java method compiled into a {@link MethodHandle}, route-key extractor, required-field
 * plan, and pre-built JSON Schema at registration time so invocations pay no reflection. Public only to be reachable
 * across packages; not API.
 */
public final class ToolMethod {
    private static final Object[] EMPTY_ARGS = {};
    private static final String[] NO_REQUIRED_FIELDS = {};

    private enum Shape { ZERO_ARG, SINGLE_DTO, PARAMS }

    private final Method method;
    private final McpToolDefinition definition;
    private final Shape shape;
    private final boolean takesContext;
    private final boolean returnsVoid;
    private final Type dtoType;
    private final String[] requiredDtoFields;
    private final List<JsonSchema.ParamSpec> params;
    private final MethodHandle handle;
    /** {@code (Object) long} reading the route key from the DTO, or from the parameter at {@link #routeKeyIndex}. */
    private final MethodHandle routeKeyReader;
    private final int routeKeyIndex;

    private ToolMethod(Method method, McpToolDefinition definition, Shape shape, boolean takesContext,
            boolean returnsVoid, Type dtoType, String[] requiredDtoFields, List<JsonSchema.ParamSpec> params,
            MethodHandle handle, MethodHandle routeKeyReader, int routeKeyIndex) {
        this.method = method;
        this.definition = definition;
        this.shape = shape;
        this.takesContext = takesContext;
        this.returnsVoid = returnsVoid;
        this.dtoType = dtoType;
        this.requiredDtoFields = requiredDtoFields;
        this.params = params;
        this.handle = handle;
        this.routeKeyReader = routeKeyReader;
        this.routeKeyIndex = routeKeyIndex;
    }

    /** Scans {@code target}, its superclasses and interfaces for {@link McpTool} methods, in name order, compiling each once. */
    public static List<ToolMethod> scan(Object target) {
        Objects.requireNonNull(target, "target");
        return compileAll(target, target.getClass());
    }

    /**
     * Scans {@code targetType}, its superclasses and interfaces for {@link McpTool} methods without requiring an
     * instance, compiling JSON Schemas, parameter decoders and {@link McpTool#routeKey()} extractors for stateless
     * schema export or gateway forwarding.
     */
    public static List<ToolMethod> scanType(Class<?> targetType) {
        Objects.requireNonNull(targetType, "targetType");
        return compileAll(null, targetType);
    }

    private static List<ToolMethod> compileAll(Object target, Class<?> rootType) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Class<?>> visitedInterfaces = new HashSet<>();
        if (rootType.isInterface()) {
            collectInterfaceMethods(rootType, seen, visitedInterfaces, methods);
        } else {
            for (Class<?> type = rootType; type != null && type != Object.class; type = type.getSuperclass()) {
                addAnnotatedMethods(type, seen, methods);
            }
            for (Class<?> type = rootType; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) {
                    collectInterfaceMethods(iface, seen, visitedInterfaces, methods);
                }
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        List<ToolMethod> compiled = new ArrayList<>(methods.size());
        for (Method method : methods) compiled.add(compile(target, method));
        return List.copyOf(compiled);
    }

    private static void collectInterfaceMethods(Class<?> iface, Set<String> seen,
            Set<Class<?>> visited, List<Method> out) {
        if (!visited.add(iface)) return;
        addAnnotatedMethods(iface, seen, out);
        for (Class<?> superIface : iface.getInterfaces()) {
            collectInterfaceMethods(superIface, seen, visited, out);
        }
    }

    private static void addAnnotatedMethods(Class<?> type, Set<String> seen, List<Method> out) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(McpTool.class)) continue;
            if (seen.add(method.getName() + List.of(method.getParameterTypes()))) out.add(method);
        }
    }

    private static ToolMethod compile(Object target, Method method) {
        if (Modifier.isStatic(method.getModifiers())) throw invalid(method, "must not be static");
        McpTool ann = method.getAnnotation(McpTool.class);
        String name = ann.name().isEmpty() ? method.getName() : ann.name();
        Parameter[] allParams = method.getParameters();
        boolean takesContext = allParams.length > 0 && allParams[0].getType() == McpToolContext.class;
        int start = takesContext ? 1 : 0;
        for (int i = start; i < allParams.length; i++) {
            if (allParams[i].getType() == McpToolContext.class) {
                throw invalid(method, "may take McpToolContext only as its first parameter");
            }
        }
        int argCount = allParams.length - start;
        Shape shape;
        Type dtoType = null;
        String[] requiredDtoFields = NO_REQUIRED_FIELDS;
        List<JsonSchema.ParamSpec> paramSpecs = List.of();
        String schema;
        if (argCount == 0) {
            shape = Shape.ZERO_ARG;
            schema = JsonSchema.EMPTY_OBJECT;
        } else if (argCount == 1 && isUnwrappedDto(allParams[start])) {
            shape = Shape.SINGLE_DTO;
            Class<?> rawDtoClass = allParams[start].getType();
            dtoType = allParams[start].getParameterizedType();
            requiredDtoFields = collectRequiredDtoFields(rawDtoClass);
            schema = JsonSchema.forObjectType(rawDtoClass);
        } else {
            shape = Shape.PARAMS;
            List<JsonSchema.ParamSpec> specs = new ArrayList<>(argCount);
            Set<String> paramNames = new HashSet<>();
            for (int i = start; i < allParams.length; i++) {
                Parameter p = allParams[i];
                McpParam paramAnn = p.getAnnotation(McpParam.class);
                String paramName = paramAnn != null && !paramAnn.name().isEmpty() ? paramAnn.name() : null;
                if (paramName == null) {
                    if (!p.isNamePresent()) {
                        throw invalid(method, "parameter #" + (i - start)
                                + " has no name; compile with -parameters or set @McpParam(name = ...)");
                    }
                    paramName = p.getName();
                }
                if (paramName.isBlank() || !paramNames.add(paramName)) {
                    throw invalid(method, "has duplicate or blank parameter name '" + paramName + "'");
                }
                String desc = paramAnn != null ? paramAnn.value() : "";
                boolean required = p.getType().isPrimitive()
                        || (p.getType() != Optional.class && (paramAnn == null || paramAnn.required()));
                specs.add(JsonSchema.ParamSpec.of(p, paramName, desc, required));
            }
            paramSpecs = List.copyOf(specs);
            schema = JsonSchema.forParameters(paramSpecs);
        }

        McpToolDefinition def = new McpToolDefinition(name, ann.description(), ann.group(),
                ann.readOnly(), ann.destructive(), ann.idempotent(), schema);
        MethodHandle handle = target != null ? buildHandle(target, method, shape, takesContext, argCount) : null;
        MethodHandle routeKeyReader = null;
        int routeKeyIndex = -1;
        String routeKey = ann.routeKey();
        if (!routeKey.isEmpty()) {
            switch (shape) {
                case ZERO_ARG -> throw invalid(method, "routes by " + routeKey + " but takes no arguments");
                case SINGLE_DTO -> routeKeyReader = routeKeyReader(method, allParams[start].getType(), routeKey);
                case PARAMS -> {
                    for (int i = 0; i < paramSpecs.size(); i++) {
                        Parameter rawParam = allParams[start + i];
                        if (paramSpecs.get(i).name().equals(routeKey)
                                || (rawParam.isNamePresent() && rawParam.getName().equals(routeKey))) {
                            routeKeyIndex = i;
                            break;
                        }
                    }
                    if (routeKeyIndex < 0) throw invalid(method, "routes by " + routeKey + ", which is none of its parameters");
                    Class<?> type = paramSpecs.get(routeKeyIndex).rawType();
                    try {
                        routeKeyReader = routeKeyType(MethodHandles.identity(type));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException(describe(method) + " cannot route by parameter " + routeKey
                                + ": it must be an integral type or its box", e);
                    }
                }
            }
        }
        return new ToolMethod(method, def, shape, takesContext, method.getReturnType() == void.class,
                dtoType, requiredDtoFields, paramSpecs, handle, routeKeyReader, routeKeyIndex);
    }

    private static String[] collectRequiredDtoFields(Class<?> type) {
        String pkg = type.getPackageName();
        if (pkg.startsWith("java.") || pkg.startsWith("javax.")) return NO_REQUIRED_FIELDS;
        List<String> required = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                McpParam param = component.getAnnotation(McpParam.class);
                String propName = param != null && !param.name().isEmpty() ? param.name() : component.getName();
                Class<?> rawType = component.getType();
                if (rawType.isPrimitive() || (rawType != Optional.class && (param == null || param.required()))) {
                    required.add(propName);
                }
            }
        } else {
            List<Class<?>> hierarchy = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
            Set<String> seen = new HashSet<>();
            for (int i = hierarchy.size() - 1; i >= 0; i--) {
                for (Field field : hierarchy.get(i).getDeclaredFields()) {
                    int mod = field.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) continue;
                    if (!seen.add(field.getName())) continue;
                    McpParam param = field.getAnnotation(McpParam.class);
                    String propName = param != null && !param.name().isEmpty() ? param.name() : field.getName();
                    Class<?> rawType = field.getType();
                    if (rawType.isPrimitive() || (rawType != Optional.class && (param == null || param.required()))) {
                        required.add(propName);
                    }
                }
            }
        }
        return required.isEmpty() ? NO_REQUIRED_FIELDS : required.toArray(String[]::new);
    }

    /**
     * {@code (Object) long} reading {@code name} from a {@code type}: a record component (by Java name or
     * {@link McpParam#name()}), a no-argument method of that name, its getter {@code getName()}, or a field (by Java
     * name or {@link McpParam#name()}), declared by the type or a superclass.
     */
    private static MethodHandle routeKeyReader(Method tool, Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                McpParam ann = rc.getAnnotation(McpParam.class);
                if (rc.getName().equals(name) || (ann != null && ann.name().equals(name))) {
                    try {
                        Method accessor = rc.getAccessor();
                        accessor.setAccessible(true);
                        return routeKeyType(MethodHandles.lookup().unreflect(accessor));
                    } catch (IllegalAccessException | RuntimeException e) {
                        throw new IllegalArgumentException(describe(tool) + " cannot route by " + type.getName() + "." + name
                                + ": it must be an integral type or its box", e);
                    }
                }
            }
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method found = null;
            for (Method m : current.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                if (m.getName().equals(name)) found = m;
                else if (found == null && m.getName().equals(getter)) found = m;
            }
            try {
                if (found != null) {
                    found.setAccessible(true);
                    return routeKeyType(MethodHandles.lookup().unreflect(found));
                }
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    McpParam ann = field.getAnnotation(McpParam.class);
                    if (field.getName().equals(name) || (ann != null && ann.name().equals(name))) {
                        field.setAccessible(true);
                        return routeKeyType(MethodHandles.lookup().unreflectGetter(field));
                    }
                }
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalArgumentException(describe(tool) + " cannot route by " + type.getName() + "." + name
                        + ": it must be an integral type or its box", e);
            }
        }
        throw invalid(tool, "routes by " + name + ", which " + type.getName() + " has neither as a method, getter nor field");
    }

    private static MethodHandle routeKeyType(MethodHandle reader) {
        Class<?> type = reader.type().returnType();
        if (type == float.class || type == double.class || type == boolean.class || type == char.class
                || type == Float.class || type == Double.class || type == Boolean.class || type == Character.class) {
            throw new IllegalArgumentException("not an integral type: " + type.getName());
        }
        return reader.asType(MethodType.methodType(long.class, Object.class));
    }

    private static MethodHandle buildHandle(Object target, Method method, Shape shape,
            boolean takesContext, int argCount) {
        try {
            method.setAccessible(true);
            MethodHandle mh = MethodHandles.lookup().unreflect(method).bindTo(target);
            if (!takesContext) mh = MethodHandles.dropArguments(mh, 0, McpToolContext.class);
            return switch (shape) {
                case ZERO_ARG -> mh.asType(MethodType.methodType(Object.class, McpToolContext.class));
                case SINGLE_DTO -> mh.asType(MethodType.methodType(Object.class, McpToolContext.class, Object.class));
                case PARAMS -> mh.asSpreader(Object[].class, argCount)
                        .asType(MethodType.methodType(Object.class, McpToolContext.class, Object[].class));
            };
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot compile @McpTool " + describe(method), e);
        }
    }

    private static boolean isUnwrappedDto(Parameter parameter) {
        McpParam ann = parameter.getAnnotation(McpParam.class);
        if (ann != null && !ann.name().isEmpty()) return false;
        Class<?> raw = parameter.getType();
        if (raw.isPrimitive() || BasicAiCodec.isScalar(raw) || raw.isEnum() || raw.isArray() || raw.isInterface()) {
            return false;
        }
        if (raw == Object.class || raw == Optional.class
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            return false;
        }
        String pkg = raw.getPackageName();
        return !pkg.startsWith("java.") && !pkg.startsWith("javax.");
    }

    /** Validates that {@link BasicAiCodec} can decode all parameter types and encode synchronous return types. */
    public void verifyBasicCodec(boolean hasResultAdapter) {
        switch (shape) {
            case ZERO_ARG -> {}
            case SINGLE_DTO -> {
                if (!BasicAiCodec.supports(dtoType)) {
                    throw invalid(method, "takes " + dtoType.getTypeName()
                            + ", which the basic codec cannot read; configure an AiCodec");
                }
            }
            case PARAMS -> {
                for (JsonSchema.ParamSpec p : params) {
                    if (!BasicAiCodec.supports(p.type())) {
                        throw invalid(method, "takes parameter '" + p.name() + "' of type " + p.type().getTypeName()
                                + ", which the basic codec cannot read; configure an AiCodec");
                    }
                }
            }
        }
        if (handle != null && !returnsVoid && !hasResultAdapter) {
            Type ret = method.getGenericReturnType();
            if (ret != Object.class && ret != McpToolResult.class && !BasicAiCodec.supports(ret)) {
                throw invalid(method, "returns " + ret.getTypeName()
                        + ", which the basic codec cannot write; configure an AiCodec");
            }
        }
    }

    public Method method() { return method; }

    public McpToolDefinition definition() { return definition; }

    /** Whether the method answers asynchronously via {@link McpToolContext} (void with context parameter). */
    public boolean isAsync() { return returnsVoid && takesContext; }

    public boolean returnsVoid() { return returnsVoid; }

    /** Whether the tool names a route key with {@link McpTool#routeKey()}. */
    public boolean hasRouteKey() { return routeKeyReader != null; }

    /**
     * Reads the route key from arguments decoded by {@link #decodeArguments}; throws {@link IllegalArgumentException}
     * when it is null. Only for a tool that {@link #hasRouteKey()}.
     */
    public long routeKey(Object[] decodedArgs) {
        Object source = routeKeyIndex >= 0 ? decodedArgs[routeKeyIndex] : decodedArgs[0];
        if (source == null || source instanceof Optional<?>) {
            throw new IllegalArgumentException("Missing route key '" + method.getAnnotation(McpTool.class).routeKey() + "'");
        }
        try {
            return (long) routeKeyReader.invokeExact(source);
        } catch (NullPointerException missing) {
            throw new IllegalArgumentException("Missing route key '" + method.getAnnotation(McpTool.class).routeKey() + "'");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    /** Decodes raw JSON arguments into the Java argument array passed to {@link #invoke}. */
    public Object[] decodeArguments(Map<String, Object> rawArguments, String rawJson, AiCodec codec) throws Exception {
        return switch (shape) {
            case ZERO_ARG -> EMPTY_ARGS;
            case SINGLE_DTO -> {
                for (String reqField : requiredDtoFields) {
                    if (rawArguments.get(reqField) == null) {
                        throw new IllegalArgumentException("Missing required argument '" + reqField + "'");
                    }
                }
                Object dto;
                if (codec == BasicAiCodec.INSTANCE) {
                    dto = BasicAiCodec.convert(rawArguments, dtoType);
                } else {
                    String json = rawJson != null ? rawJson : Json.write(rawArguments);
                    dto = codec.decode(json, dtoType);
                }
                if (dto == null) throw new IllegalArgumentException("Codec decoded null for " + dtoType.getTypeName());
                yield new Object[]{dto};
            }
            case PARAMS -> {
                Object[] values = new Object[params.size()];
                for (int i = 0; i < params.size(); i++) {
                    JsonSchema.ParamSpec spec = params.get(i);
                    boolean present = rawArguments.containsKey(spec.name());
                    Object rawVal = rawArguments.get(spec.name());
                    if (!present || rawVal == null) {
                        if (spec.rawType() == Optional.class) {
                            values[i] = Optional.empty();
                        } else if (spec.required()) {
                            throw new IllegalArgumentException("Missing required argument '" + spec.name() + "'");
                        } else {
                            values[i] = null;
                        }
                    } else if (codec == BasicAiCodec.INSTANCE
                            || BasicAiCodec.isScalar(spec.rawType())
                            || spec.rawType().isEnum()) {
                        values[i] = BasicAiCodec.convert(rawVal, spec.type());
                    } else {
                        values[i] = codec.decode(Json.write(rawVal), spec.type());
                    }
                }
                yield values;
            }
        };
    }

    /** Calls the compiled method handle with already-decoded arguments; void returns null. */
    public Object invoke(McpToolContext context, Object[] decodedArgs) throws Exception {
        try {
            return switch (shape) {
                case ZERO_ARG -> (Object) handle.invokeExact(context);
                case SINGLE_DTO -> (Object) handle.invokeExact(context, decodedArgs[0]);
                case PARAMS -> (Object) handle.invokeExact(context, decodedArgs);
            };
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private static IllegalArgumentException invalid(Method method, String problem) {
        return new IllegalArgumentException(describe(method) + " " + problem);
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    @Override public String toString() { return describe(method); }
}
