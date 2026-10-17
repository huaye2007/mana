package cn.managame.runtime.internal;

import static cn.managame.runtime.internal.Reflection.bind;
import static cn.managame.runtime.internal.Reflection.invalid;
import static cn.managame.runtime.internal.Reflection.methods;
import static cn.managame.runtime.internal.Reflection.requireHttpMethod;
import static cn.managame.runtime.internal.Reflection.requireKeyRule;

import cn.managame.runtime.context.Context;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpRequestCodec;
import cn.managame.runtime.internal.RuntimeBindings.HttpBinding;
import cn.managame.runtime.internal.RuntimeBindings.HttpEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Compiles {@code @HttpHandler} instances into exact method/path → invocation bindings. */
final class HttpCompiler {
    /** Where each Java parameter of an HTTP method comes from. */
    private static final int FROM_CONTEXT = 0, FROM_REQUEST = 1;

    private HttpCompiler() {}

    static Map<HttpEndpoint, HttpBinding> compile(List<Object> handlers, Set<Integer> domains, HttpRequestCodec requests) {
        Map<HttpEndpoint, HttpBinding> bindings = new HashMap<>();
        for (Object target : handlers) {
            HttpHandler owner = target.getClass().getAnnotation(HttpHandler.class);
            if (owner == null) throw invalid("Missing @HttpHandler: " + target.getClass());
            requireKeyRule(owner.routeKey(), owner.routeKeyMethod(), "HTTP");
            for (Method method : methods(target)) {
                HttpMethod entry = method.getAnnotation(HttpMethod.class);
                if (entry == null) continue;
                HttpEndpoint endpoint = new HttpEndpoint(entry.method().name(), entry.value());
                if (bindings.putIfAbsent(endpoint, compileMethod(target, owner, entry, method, domains, requests)) != null)
                    throw invalid("Duplicate HTTP method/path: " + endpoint.method() + " " + endpoint.path());
            }
        }
        return Map.copyOf(bindings);
    }

    private static HttpBinding compileMethod(Object target, HttpHandler owner, HttpMethod entry, Method method,
                                             Set<Integer> domains, HttpRequestCodec requests) {
        requireHttpMethod(method);
        requireKeyRule(entry.routeKey(), entry.routeKeyMethod(), "HTTP");
        boolean overrideKey = !entry.routeKey().isEmpty() || !entry.routeKeyMethod().isEmpty();
        HttpRouteKey key = compileKey(target, overrideKey ? entry.routeKey() : owner.routeKey(),
                overrideKey ? entry.routeKeyMethod() : owner.routeKeyMethod());
        int domain = entry.domain() != 0 ? entry.domain() : owner.domain();
        if (!domains.contains(domain)) throw invalid("Unregistered HTTP domain: " + method);
        String path = entry.value();
        if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid("Invalid HTTP path: " + method);
        Class<?> contextType = null;
        boolean requestSeen = false;
        Function<FullHttpRequest, Object> decoder = request -> null;
        int[] arguments = new int[method.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == FullHttpRequest.class && !requestSeen) {
                requestSeen = true;
                arguments[i] = FROM_REQUEST;
                decoder = request -> request;
            } else if (contextType == null && Context.class.isAssignableFrom(type)
                    && (type.isAssignableFrom(HttpContext.class) || HttpContext.class.isAssignableFrom(type))) {
                contextType = type;
                arguments[i] = FROM_CONTEXT;
            } else if (!requestSeen && !Context.class.isAssignableFrom(type) && !type.isPrimitive()
                    && !HttpObject.class.isAssignableFrom(type)) {
                requestSeen = true;
                arguments[i] = FROM_REQUEST;
                Type declared = method.getGenericParameterTypes()[i];
                requireConcrete(declared);
                decoder = Objects.requireNonNull(requests.decoder(declared), "HTTP decoder");
            } else {
                throw invalid("Invalid HTTP parameters: " + method);
            }
        }
        MethodHandle handle = bind(target, method);
        boolean returnsResult = method.getReturnType() != void.class;
        if (!returnsResult) handle = MethodHandles.filterReturnValue(handle, MethodHandles.constant(Object.class, null));
        handle = handle.asType(MethodType.genericMethodType(arguments.length));
        handle = MethodHandles.permuteArguments(handle, MethodType.methodType(Object.class, Object.class, Object.class), arguments);
        return new HttpBinding(domain, contextType, returnsResult, handle, key, decoder);
    }

    private static void requireConcrete(Type type) {
        if (type instanceof TypeVariable<?> || type instanceof WildcardType)
            throw invalid("HTTP payload requires a concrete type: " + type);
        if (type instanceof ParameterizedType parameterized)
            for (Type argument : parameterized.getActualTypeArguments()) requireConcrete(argument);
        if (type instanceof GenericArrayType array) requireConcrete(array.getGenericComponentType());
    }

    private static HttpRouteKey compileKey(Object target, String field, String name) {
        if (!field.isEmpty()) return HttpRouteKey.field(field);
        if (name.isEmpty()) return null;
        try {
            Method method = target.getClass().getMethod(name, FullHttpRequest.class);
            if (Modifier.isStatic(method.getModifiers()) || method.isVarArgs()
                    || (method.getReturnType() != long.class && method.getReturnType() != Long.class))
                throw invalid("Expected public instance long/Long RouteKey method: " + method);
            MethodHandle handle = bind(target, method).asType(MethodType.methodType(long.class, Object.class));
            return request -> (long) handle.invokeExact((Object) request);
        } catch (NoSuchMethodException cause) {
            throw invalid("Missing public HTTP RouteKey method: " + name);
        }
    }
}
