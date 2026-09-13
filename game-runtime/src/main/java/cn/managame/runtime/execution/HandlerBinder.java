package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteType;
import cn.managame.runtime.route.UnspecifiedRouteKeyResolver;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import static cn.managame.runtime.execution.HandlerBindings.*;

/** Initialization-only validation and binding. No runtime class generation. */
final class HandlerBinder {
    private final ProtocolRegistry protocols;
    private final ParameterResolverRegistry parameters;
    private final Map<Class<?>, RouteResolver> resolvers;
    private final Map<Class<? extends RouteType>, RouteResolver> defaults;

    HandlerBinder(ProtocolRegistry protocols, ParameterResolverRegistry parameters,
                  Map<Class<?>, RouteResolver> resolvers, Map<Class<? extends RouteType>, RouteResolver> defaults) {
        this.protocols = protocols;
        this.parameters = parameters;
        this.resolvers = resolvers;
        this.defaults = defaults;
    }

    RuntimeBindings bind(List<Object> handlers, Set<Class<?>> eventTypes) {
        Map<Class<?>, CommandBinding> commands = new HashMap<>();
        Map<Class<?>, List<EventBinding>> events = new HashMap<>();
        eventTypes.forEach(type -> events.put(type, new ArrayList<>()));
        List<CronBinding> crons = new ArrayList<>();
        Set<Object> instances = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object bean : handlers) {
            if (!instances.add(bean)) throw new IllegalArgumentException("Duplicate handler instance");
            Handler handler = bean.getClass().getAnnotation(Handler.class);
            EventHandler eventHandler = bean.getClass().getAnnotation(EventHandler.class);
            if (handler != null && eventHandler != null)
                throw new IllegalArgumentException("Command and Event handlers must be independent");
            for (Method method : HandlerMethodScanner.scan(bean.getClass())) {
                validateEntry(method);
                if (method.isAnnotationPresent(HandlerMethod.class)) {
                    CommandBinding binding = bindCommand(bean, method, handler);
                    Class<?> request = binding.protocol().messageType();
                    if (commands.putIfAbsent(request, binding) != null)
                        throw new IllegalArgumentException("Conflicting command handler: " + request);
                } else if (method.isAnnotationPresent(EventMethod.class)) {
                    EventBinding binding = bindEvent(bean, method, eventHandler, eventTypes);
                    events.get(method.getParameterTypes()[0]).add(binding);
                } else {
                    crons.add(bindCron(bean, method));
                }
            }
        }
        return new RuntimeBindings(new CommandRegistry(commands), new EventRegistry(events), crons);
    }

    private CommandBinding bindCommand(Object bean, Method method, Handler handler) {
        if (handler == null) throw new IllegalArgumentException("Missing @Handler: " + method);
        List<Class<?>> requests = Arrays.stream(method.getParameterTypes())
                .filter(type -> protocols.find(type).map(p -> p.type() == ProtocolType.REQUEST).orElse(false)).toList();
        if (requests.size() != 1)
            throw new IllegalArgumentException("Command needs exactly one registered request: " + method);
        Class<?> request = requests.getFirst();
        RouteResolver resolver = routeResolver(handler.routeKeyResolver(), handler.routeType(), defaults);
        ArgumentPlan plan = bindArguments(method, request, resolver.source());
        return new CommandBinding(protocols.require(request), handler.routeType(), resolver,
                plan.slots().get(plan.routeSlot()),
                HandlerInvokers.command(target(bean, method), plan.slots(), plan.arguments(), plan.routeSlot()),
                method.toGenericString());
    }

    private EventBinding bindEvent(Object bean, Method method, EventHandler handler, Set<Class<?>> eventTypes) {
        if (handler == null || method.getParameterCount() != 1 || !eventTypes.contains(method.getParameterTypes()[0]))
            throw new IllegalArgumentException("EventMethod needs one registered event and @EventHandler: " + method);
        RouteResolver resolver = routeResolver(handler.routeKeyResolver(), handler.routeType(), Map.of());
        if (!resolver.source().isAssignableFrom(method.getParameterTypes()[0]))
            throw new IllegalArgumentException("Invalid event route source: " + method);
        return new EventBinding(handler.routeType(), resolver, HandlerInvokers.event(target(bean, method)),
                method.getAnnotation(EventMethod.class).order(), method.toGenericString());
    }

    private CronBinding bindCron(Object bean, Method method) {
        if (method.getParameterCount() != 0) throw new IllegalArgumentException("Cron must have no parameters: " + method);
        Cron cron = method.getAnnotation(Cron.class);
        return new CronBinding(CronExpression.parse(cron.value()), new Route(cron.routeType(), cron.routeKey()),
                HandlerInvokers.cron(target(bean, method)), method.toGenericString());
    }

    private record ArgumentPlan(List<ArgumentSlot> slots, int[] arguments, int routeSlot) {}

    private ArgumentPlan bindArguments(Method method, Class<?> request, Class<?> routeSource) {
        Map<Class<?>, Integer> types = new LinkedHashMap<>();
        List<ArgumentSlot> slots = new ArrayList<>();
        Class<?>[] declared = method.getParameterTypes();
        int[] arguments = new int[declared.length];
        for (int i = 0; i < declared.length; i++) arguments[i] = slot(declared[i], request, types, slots);
        List<Class<?>> sources = types.keySet().stream().filter(routeSource::isAssignableFrom).toList();
        if (sources.size() > 1) throw new IllegalArgumentException("Ambiguous route source: " + method);
        int routeSlot = sources.isEmpty() ? slot(routeSource, request, types, slots) : types.get(sources.getFirst());
        if (!slots.get(routeSlot).routeSource())
            throw new IllegalArgumentException("Route source must use registerRouteSource: " + method);
        return new ArgumentPlan(List.copyOf(slots), arguments, routeSlot);
    }

    private int slot(Class<?> type, Class<?> request, Map<Class<?>, Integer> types, List<ArgumentSlot> slots) {
        Integer existing = types.get(type);
        if (existing != null) return existing;
        Function<CommandHandlerInvocation, Object> resolver;
        boolean routeSource = type == request;
        if (type == request) resolver = CommandHandlerInvocation::request;
        else {
            var bound = parameters.bind(type);
            Class<?> valueType = boxed(type);
            boolean primitive = type.isPrimitive();
            resolver = invocation -> {
                Object value = bound.resolver().resolve(invocation);
                if (primitive) Objects.requireNonNull(value, "Primitive semantic parameter resolved to null");
                return valueType.cast(value);
            };
            routeSource = bound.routeSource();
        }
        int index = slots.size();
        types.put(type, index);
        slots.add(new ArgumentSlot(resolver, routeSource));
        return index;
    }

    private RouteResolver routeResolver(Class<?> type, Class<? extends RouteType> routeType, Map<Class<? extends RouteType>, RouteResolver> fallback) {
        RouteResolver result = type == UnspecifiedRouteKeyResolver.class ? fallback.get(routeType) : resolvers.get(type);
        if (result == null) throw new IllegalArgumentException("Missing RouteKeyResolver for " + routeType + ": " + type);
        return result;
    }

    private static void validateEntry(Method method) {
        int triggers = (method.isAnnotationPresent(HandlerMethod.class) ? 1 : 0)
                + (method.isAnnotationPresent(EventMethod.class) ? 1 : 0)
                + (method.isAnnotationPresent(Cron.class) ? 1 : 0);
        if (triggers != 1 || method.getReturnType() != void.class || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers()) || method.isVarArgs())
            throw new IllegalArgumentException("Invalid handler method: " + method);
        for (Class<?> parameter : method.getParameterTypes())
            if (HandlerContext.class.isAssignableFrom(parameter))
                throw new IllegalArgumentException("HandlerContext cannot be injected: " + method);
    }

    private static MethodHandle target(Object bean, Method method) {
        try {
            return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                    .unreflect(method).bindTo(bean);
        } catch (IllegalAccessException error) {
            throw new IllegalArgumentException("Inaccessible handler: " + method, error);
        }
    }
}
