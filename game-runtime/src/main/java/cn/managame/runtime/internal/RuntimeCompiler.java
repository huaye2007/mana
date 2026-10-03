package cn.managame.runtime.internal;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.protocol.ProtocolRegistrar;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.time.GameTime;
import cn.managame.runtime.timer.Cron;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.DefaultHttpContext;
import cn.managame.runtime.http.HttpResultCodec;
import io.netty.handler.codec.http.FullHttpRequest;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Builds immutable runtime bindings. Internal implementation, not an application API. */
public final class RuntimeCompiler {
    private RuntimeCompiler() {}
    public static GameRuntime build(List<RouteDomain> domains, List<RouteExecutorBinding> executors,
            List<ProtocolProvider> providers, List<RouteKeyBinding<?>> keys,
            List<Object> handlers, List<HandlerArgumentBinding<?>> arguments, HandlerContextFactory handlerContexts,
            List<Object> events, List<Object> crons,
            List<Object> httpHandlers, HttpContextFactory httpContexts, HttpResultCodec httpResults,
            ZoneId cronZone, RuntimeErrorHandler errors) {
        Set<Integer> ids = new HashSet<>();
        for (var d : domains) if (!ids.add(d.id())) throw invalid("Duplicate domain " + d.id());
        Map<Integer, RouteExecutor> routes = new HashMap<>();
        for (var b : executors) for (int domain : b.routeDomains()) {
            if (!ids.contains(domain)) throw invalid("Executor binds unknown domain " + domain);
            if (routes.putIfAbsent(domain, b.executor()) != null) throw invalid("Duplicate executor binding " + domain);
        }
        if (!routes.keySet().equals(ids)) throw invalid("Every domain needs exactly one executor");
        Registry registry = new Registry(providers);
        Map<Class<?>, HandlerArgumentBinding<?>> argumentBindings = new HashMap<>();
        for (var argument : arguments) {
            if (registry.get(argument.type()) != null) throw invalid("Handler argument is also a protocol: " + argument.type());
            if (argumentBindings.putIfAbsent(argument.type(), argument) != null)
                throw invalid("Duplicate Handler argument binding: " + argument.type());
        }
        Map<Class<?>, RouteKeyBinding<?>> routeKeys = new HashMap<>();
        for (var key : keys) if (routeKeys.putIfAbsent(key.messageType(), key) != null) throw invalid("Duplicate route-key binding");
        Map<Class<?>, HandlerBinding> compiled = new HashMap<>();
        for (Object target : handlers) {
            Handler annotation = target.getClass().getAnnotation(Handler.class);
            if (annotation == null) throw invalid("Missing @Handler: " + target.getClass());
            validateMessageKey(annotation.routeKey(), annotation.routeKeyMethod());
            for (Method m : methods(target)) {
                HandlerMethod entry = m.getAnnotation(HandlerMethod.class);
                if (entry == null) continue;
                validate(m);
                int domain = entry.domain() != 0 ? entry.domain() : annotation.domain();
                if (!ids.contains(domain)) throw invalid("Unregistered handler domain: " + m);
                Class<?> contextType = null, messageType = null;
                int[] sources = new int[m.getParameterCount()];
                List<HandlerArgumentBinding<?>> custom = new ArrayList<>();
                Set<Class<?>> customTypes = new HashSet<>();
                for (int i = 0; i < m.getParameterCount(); i++) {
                    Class<?> type = m.getParameterTypes()[i];
                    if (Context.class.isAssignableFrom(type)) {
                        if (contextType != null || !(type.isAssignableFrom(HandlerContext.class) || HandlerContext.class.isAssignableFrom(type)))
                            throw invalid("Invalid handler context: " + m);
                        contextType = type; sources[i] = 0;
                    } else if (registry.get(type) != null) {
                        if (messageType != null) throw invalid("Multiple message parameters: " + m);
                        messageType = type; sources[i] = 1;
                    } else {
                        var argument = argumentBindings.get(type);
                        if (argument == null) throw invalid("Unregistered Handler argument: " + type.getName() + " at " + m);
                        if (!customTypes.add(type)) throw invalid("Repeated Handler argument type: " + type.getName());
                        custom.add(argument); sources[i] = 2;
                    }
                }
                if (messageType == null || registry.get(messageType) == null) throw invalid("Handler needs one registered message: " + m);
                if (registry.get(messageType).type() == ProtocolType.RESPONSE) throw invalid("Response cannot be an inbound handler: " + m);
                validateMessageKey(entry.routeKey(), entry.routeKeyMethod());
                boolean overrideKey = !entry.routeKey().isEmpty() || !entry.routeKeyMethod().isEmpty();
                String keyField = overrideKey ? entry.routeKey() : annotation.routeKey();
                String keyMethod = overrideKey ? entry.routeKeyMethod() : annotation.routeKeyMethod();
                RouteKeyBinding<?> key = !keyField.isEmpty() ? RouteKeyBinding.ofField(messageType, keyField)
                        : !keyMethod.isEmpty() ? RouteKeyBinding.ofMethod(messageType, keyMethod) : null;
                if (key != null && routeKeys.putIfAbsent(messageType, key) != null)
                    throw invalid("Duplicate route-key binding: " + messageType.getName());
                MethodHandle handle = bind(target, m);
                handle = handle.asType(MethodType.genericMethodType(sources.length).changeReturnType(void.class));
                int customIndex = 0;
                for (int i = 0; i < sources.length; i++) if (sources[i] == 2) {
                    MethodHandle getter = MethodHandles.insertArguments(MethodHandles.arrayElementGetter(Object[].class), 1, customIndex++);
                    handle = MethodHandles.filterArguments(handle, i, getter);
                }
                handle = MethodHandles.permuteArguments(handle,
                        MethodType.methodType(void.class, Object.class, Object.class, Object[].class), sources);
                if (compiled.putIfAbsent(messageType, new HandlerBinding(domain, contextType, List.copyOf(custom), handle)) != null) throw invalid("Duplicate handler " + messageType);
            }
        }
        Map<Class<?>, List<EventBinding>> compiledEvents = new HashMap<>();
        for (Object target : events) for (Method m : methods(target)) {
            EventMethod event = m.getAnnotation(EventMethod.class);
            if (event == null) continue;
            validate(m);
            if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) throw invalid("Event method needs one Event: " + m);
            compiledEvents.computeIfAbsent(m.getParameterTypes()[0], _ -> new ArrayList<>())
                .add(new EventBinding(event.order(), bind(target, m).asType(MethodType.methodType(void.class, Object.class))));
        }
        compiledEvents.replaceAll((_, value) -> value.stream().sorted(Comparator.comparingInt(EventBinding::order)).toList());
        List<CronBinding> compiledCrons = new ArrayList<>();
        Set<CronKey> cronKeys = new HashSet<>();
        for (Object target : crons) for (Method m : methods(target)) {
            Cron cron = m.getAnnotation(Cron.class);
            if (cron == null) continue;
            validate(m);
            if (m.getParameterCount() != 0 || !ids.contains(cron.domain()) || cron.routeKey() == 0) throw invalid("Invalid cron method: " + m);
            CronSchedule schedule = new CronSchedule(cron.value(), cronZone);
            schedule.next(Instant.ofEpochMilli(GameTime.currentTimeMillis())); // validate before allocating resources
            CronKey cronKey = new CronKey(m.getDeclaringClass(), m.getName());
            if (!cronKeys.add(cronKey)) throw invalid("Duplicate cron " + cronKey);
            compiledCrons.add(new CronBinding(cronKey, cron.domain(), cron.routeKey(), schedule, bind(target, m)));
        }
        Map<HttpEndpoint, HttpBinding> compiledHttp = compileHttp(httpHandlers, ids);
        if (httpContexts == null && compiledHttp.values().stream().anyMatch(binding -> binding.routeKey() == null))
            throw invalid("HTTP endpoints without a RouteKey rule require httpContextFactory");
        if (httpContexts == null && compiledHttp.values().stream().anyMatch(binding -> binding.contextType() != null
                && !binding.contextType().isAssignableFrom(DefaultHttpContext.class)))
            throw invalid("Custom HTTP contexts require httpContextFactory");
        return new DefaultGameRuntime(Map.copyOf(routes), registry, Map.copyOf(routeKeys),
            Map.copyOf(compiled), handlerContexts, Map.copyOf(compiledEvents), List.copyOf(compiledCrons),
            Map.copyOf(compiledHttp), httpContexts, httpResults, errors);
    }

    private static Map<HttpEndpoint, HttpBinding> compileHttp(List<Object> handlers, Set<Integer> domains) {
        Map<HttpEndpoint, HttpBinding> bindings = new HashMap<>();
        for (Object target : handlers) {
            HttpHandler owner = target.getClass().getAnnotation(HttpHandler.class);
            if (owner == null) throw invalid("Missing @HttpHandler: " + target.getClass());
            validateHttpKey(owner.routeKey(), owner.routeKeyMethod());
            for (Method method : methods(target)) {
                HttpMethod entry = method.getAnnotation(HttpMethod.class);
                if (entry == null) continue;
                validateHttp(method);
                validateHttpKey(entry.routeKey(), entry.routeKeyMethod());
                boolean overrideKey = !entry.routeKey().isEmpty() || !entry.routeKeyMethod().isEmpty();
                HttpRouteKey key = compileHttpKey(target, overrideKey ? entry.routeKey() : owner.routeKey(),
                        overrideKey ? entry.routeKeyMethod() : owner.routeKeyMethod());
                int domain = entry.domain() != 0 ? entry.domain() : owner.domain();
                if (!domains.contains(domain)) throw invalid("Unregistered HTTP domain: " + method);
                String path = entry.value(), verb = entry.method().name();
                if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                        || path.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid("Invalid HTTP path: " + method);
                Class<?> contextType = null;
                boolean requestSeen = false;
                int[] arguments = new int[method.getParameterCount()];
                for (int i = 0; i < arguments.length; i++) {
                    Class<?> type = method.getParameterTypes()[i];
                    if (type == FullHttpRequest.class && !requestSeen) { requestSeen = true; arguments[i] = 1; }
                    else if (contextType == null && Context.class.isAssignableFrom(type)
                            && (type.isAssignableFrom(HttpContext.class) || HttpContext.class.isAssignableFrom(type))) {
                        contextType = type; arguments[i] = 0;
                    } else throw invalid("Invalid HTTP parameters: " + method);
                }
                MethodHandle handle = bind(target, method);
                boolean returnsResult = method.getReturnType() != void.class;
                if (!returnsResult) handle = MethodHandles.filterReturnValue(handle, MethodHandles.constant(Object.class, null));
                handle = handle.asType(MethodType.genericMethodType(arguments.length));
                handle = MethodHandles.permuteArguments(handle, MethodType.methodType(Object.class, Object.class, Object.class), arguments);
                if (bindings.putIfAbsent(new HttpEndpoint(verb, path), new HttpBinding(domain, contextType, returnsResult, handle, key)) != null)
                    throw invalid("Duplicate HTTP method/path: " + verb + " " + path);
            }
        }
        return bindings;
    }
    private static void validateMessageKey(String field, String method) {
        if ((!field.isEmpty() && field.isBlank()) || (!method.isEmpty() && method.isBlank())
                || (!field.isEmpty() && !method.isEmpty())) throw invalid("Handler RouteKey requires one field or method");
    }
    private static void validateHttpKey(String field, String method) {
        if ((!field.isEmpty() && field.isBlank()) || (!method.isEmpty() && method.isBlank())
                || (!field.isEmpty() && !method.isEmpty())) throw invalid("HTTP RouteKey requires one field or method");
    }
    private static HttpRouteKey compileHttpKey(Object target, String field, String name) {
        if (!field.isEmpty()) return HttpRouteKey.field(field);
        if (name.isEmpty()) return null;
        try {
            Method method = target.getClass().getMethod(name, FullHttpRequest.class);
            if (Modifier.isStatic(method.getModifiers()) || method.isVarArgs()
                    || (method.getReturnType() != long.class && method.getReturnType() != Long.class))
                throw invalid("Expected public instance long/Long RouteKey method: " + method);
            MethodHandle handle = bind(target, method).asType(MethodType.methodType(long.class, Object.class));
            return request -> (long) handle.invokeExact((Object) request);
        } catch (NoSuchMethodException cause) { throw invalid("Missing public HTTP RouteKey method: " + name); }
    }
    private static Set<Method> methods(Object target) {
        // Detect invalid private/static annotated methods instead of silently ignoring them.
        for (Class<?> type = target.getClass(); type != Object.class; type = type.getSuperclass())
            for (Method m : type.getDeclaredMethods())
                if (m.isAnnotationPresent(HandlerMethod.class) || m.isAnnotationPresent(EventMethod.class) || m.isAnnotationPresent(Cron.class)) validate(m);
                else if (m.isAnnotationPresent(HttpMethod.class)) validateHttp(m);
        return new LinkedHashSet<>(Arrays.asList(target.getClass().getMethods()));
    }
    private static void validate(Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class || m.isVarArgs())
            throw invalid("Expected public instance void method: " + m);
    }
    private static void validateHttp(Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.isVarArgs()
                || (m.getReturnType().isPrimitive() && m.getReturnType() != void.class)
                || io.netty.handler.codec.http.HttpObject.class.isAssignableFrom(m.getReturnType()))
            throw invalid("Expected public instance HTTP method returning void or a business object: " + m);
    }
    private static MethodHandle bind(Object target, Method method) {
        try { return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup()).unreflect(method).bindTo(target); }
        catch (IllegalAccessException e) { throw new IllegalArgumentException("Cannot access " + method, e); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    record HandlerBinding(int domain, Class<?> contextType, List<HandlerArgumentBinding<?>> arguments, MethodHandle handle) {
        private static final Object[] NO_ARGUMENTS = new Object[0];
        Object[] resolve(HandlerContext context) {
            if (arguments.isEmpty()) return NO_ARGUMENTS;
            Object[] values = new Object[arguments.size()];
            for (int i = 0; i < values.length; i++) values[i] = arguments.get(i).resolve(context);
            return values;
        }
        void invoke(Object context, Object message, Object[] arguments) throws Throwable { handle.invokeExact(context, message, arguments); }
    }
    record HttpEndpoint(String method, String path) {}
    record HttpBinding(int domain, Class<?> contextType, boolean returnsResult, MethodHandle handle, HttpRouteKey routeKey) {
        Object invoke(Object context, Object request) throws Throwable { return handle.invokeExact(context, request); }
    }
    record EventBinding(int order, MethodHandle handle) {
        void invoke(Object event) throws Throwable { handle.invokeExact(event); }
    }
    record CronKey(Class<?> type, String methodName) {}
    record CronBinding(CronKey id, int domain, long key, CronSchedule schedule, MethodHandle handle) {
        void invoke() throws Throwable { handle.invokeExact(); }
    }
    private static final class Registry implements ProtocolRegistry {
        private record Key(ProtocolType type, int command) {}
        private final Map<Key, ProtocolDescriptor<?>> byKey;
        private final Map<Class<?>, ProtocolDescriptor<?>> byType;
        private final Map<Class<?>, Class<?>> responses;
        Registry(List<ProtocolProvider> providers) {
            Map<Key, ProtocolDescriptor<?>> k = new HashMap<>();
            Map<Class<?>, ProtocolDescriptor<?>> t = new HashMap<>();
            Map<Class<?>, Class<?>> r = new HashMap<>();
            ProtocolRegistrar registrar = new ProtocolRegistrar() {
                public void register(ProtocolDescriptor<?> d) {
                    Objects.requireNonNull(d);
                    // Copy descriptors: a provider cannot mutate the frozen runtime registry.
                    ProtocolDescriptor<?> frozen = switch (Objects.requireNonNull(d.type())) {
                        case REQUEST -> Protocols.request(d.command(), d.messageType());
                        case RESPONSE -> Protocols.response(d.command(), d.messageType());
                        case NOTIFY -> Protocols.notify(d.command(), d.messageType());
                    };
                    if (k.putIfAbsent(new Key(frozen.type(), frozen.command()), frozen) != null || t.putIfAbsent(frozen.messageType(), frozen) != null)
                        throw invalid("Duplicate protocol identity/class");
                }
                public void bindResponse(Class<?> request, Class<?> response) {
                    if (r.putIfAbsent(Objects.requireNonNull(request), Objects.requireNonNull(response)) != null) throw invalid("Duplicate response binding");
                }
            };
            providers.forEach(p -> p.register(registrar));
            r.forEach((req, res) -> {
                if (t.get(req) == null || t.get(req).type() != ProtocolType.REQUEST || t.get(res) == null || t.get(res).type() != ProtocolType.RESPONSE)
                    throw invalid("Response binding must reference registered REQUEST and RESPONSE");
            });
            byKey = Map.copyOf(k); byType = Map.copyOf(t); responses = Map.copyOf(r);
        }
        public ProtocolDescriptor<?> get(ProtocolType type, int command) { return byKey.get(new Key(type, command)); }
        public ProtocolDescriptor<?> get(Class<?> type) { return byType.get(type); }
        public Class<?> getResponseType(Class<?> type) { return responses.get(type); }
    }
}
