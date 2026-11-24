package cn.managame.ai.internal;

import cn.managame.ai.AiCodec;
import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.mcp.McpToolContext;
import cn.managame.ai.mcp.McpToolDefinition;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
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
 * One {@link McpTool}-annotated Java method compiled into a {@link MethodHandle} and pre-built JSON Schema at
 * registration time so invocations pay no reflection. Public only to be reachable across packages; not API.
 */
public final class ToolMethod {
    private static final Object[] EMPTY_ARGS = {};

    private enum Shape { ZERO_ARG, SINGLE_DTO, PARAMS }

    private final Method method;
    private final McpToolDefinition definition;
    private final Shape shape;
    private final boolean takesContext;
    private final boolean returnsVoid;
    private final Type dtoType;
    private final List<JsonSchema.ParamSpec> params;
    private final MethodHandle handle;

    private ToolMethod(Method method, McpToolDefinition definition, Shape shape, boolean takesContext,
            boolean returnsVoid, Type dtoType, List<JsonSchema.ParamSpec> params, MethodHandle handle) {
        this.method = method;
        this.definition = definition;
        this.shape = shape;
        this.takesContext = takesContext;
        this.returnsVoid = returnsVoid;
        this.dtoType = dtoType;
        this.params = params;
        this.handle = handle;
    }

    /** Scans {@code target} and its superclasses for {@link McpTool} methods, in name order, compiling each once. */
    public static List<ToolMethod> scan(Object target) {
        Objects.requireNonNull(target, "target");
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(McpTool.class)) continue;
                if (seen.add(method.getName() + List.of(method.getParameterTypes()))) methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        List<ToolMethod> compiled = new ArrayList<>(methods.size());
        for (Method method : methods) compiled.add(compile(target, method));
        return List.copyOf(compiled);
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
        List<JsonSchema.ParamSpec> paramSpecs = List.of();
        String schema;
        if (argCount == 0) {
            shape = Shape.ZERO_ARG;
            schema = JsonSchema.EMPTY_OBJECT;
        } else if (argCount == 1 && isUnwrappedDto(allParams[start])) {
            shape = Shape.SINGLE_DTO;
            dtoType = allParams[start].getParameterizedType();
            schema = JsonSchema.forObjectType(allParams[start].getType());
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

        McpToolDefinition def = new McpToolDefinition(name, ann.description(), ann.group(), ann.readOnly(), schema);
        MethodHandle handle = buildHandle(target, method, shape, takesContext, argCount);
        return new ToolMethod(method, def, shape, takesContext, method.getReturnType() == void.class,
                dtoType, paramSpecs, handle);
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

    /** Validates that {@link BasicAiCodec} can decode all parameter types of this method. */
    public void verifyBasicCodec() {
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
    }

    public Method method() { return method; }

    public McpToolDefinition definition() { return definition; }

    /** Whether the method answers asynchronously via {@link McpToolContext} (void with context parameter). */
    public boolean isAsync() { return returnsVoid && takesContext; }

    public boolean returnsVoid() { return returnsVoid; }

    /** Decodes raw JSON arguments into the Java argument array passed to {@link #invoke}. */
    public Object[] decodeArguments(Map<String, Object> rawArguments, String rawJson, AiCodec codec) throws Exception {
        return switch (shape) {
            case ZERO_ARG -> EMPTY_ARGS;
            case SINGLE_DTO -> {
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
                    } else if (codec == BasicAiCodec.INSTANCE || BasicAiCodec.isScalar(spec.rawType()) || spec.rawType().isEnum()) {
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
