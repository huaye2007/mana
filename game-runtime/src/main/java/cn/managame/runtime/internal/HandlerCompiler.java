package cn.managame.runtime.internal;

import static cn.managame.runtime.internal.Reflection.bind;
import static cn.managame.runtime.internal.Reflection.invalid;
import static cn.managame.runtime.internal.Reflection.methods;
import static cn.managame.runtime.internal.Reflection.requireHandlerMethod;
import static cn.managame.runtime.internal.Reflection.requireKeyRule;

import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.internal.RuntimeBindings.HandlerBinding;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteKeyBinding;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles {@code @Handler} instances into message-type → invocation bindings. */
final class HandlerCompiler {
    /** Where each Java parameter of a Handler method comes from. */
    private static final int FROM_CONTEXT = 0, FROM_MESSAGE = 1, FROM_ARGUMENTS = 2;

    private final Set<Integer> domains;
    private final ProtocolRegistry protocols;
    private final Map<Class<?>, HandlerArgumentBinding<?>> argumentBindings = new HashMap<>();
    /** Message RouteKey rules; Handler annotations add theirs. */
    final Map<Class<?>, RouteKeyBinding<?>> routeKeys = new HashMap<>();
    final Map<Class<?>, HandlerBinding> handlers = new HashMap<>();

    HandlerCompiler(Set<Integer> domains, ProtocolRegistry protocols, List<RouteKeyBinding<?>> keys,
                    List<HandlerArgumentBinding<?>> arguments) {
        this.domains = domains;
        this.protocols = protocols;
        for (var argument : arguments) {
            if (protocols.get(argument.type()) != null) throw invalid("Handler argument is also a protocol: " + argument.type());
            if (argumentBindings.putIfAbsent(argument.type(), argument) != null)
                throw invalid("Duplicate Handler argument binding: " + argument.type());
        }
        for (var key : keys)
            if (routeKeys.putIfAbsent(key.messageType(), key) != null) throw invalid("Duplicate route-key binding");
    }

    void compile(Object target) {
        Handler annotation = target.getClass().getAnnotation(Handler.class);
        if (annotation == null) throw invalid("Missing @Handler: " + target.getClass());
        requireKeyRule(annotation.routeKey(), annotation.routeKeyMethod(), "Handler");
        for (Method m : methods(target)) {
            HandlerMethod entry = m.getAnnotation(HandlerMethod.class);
            if (entry != null) compileMethod(target, annotation, entry, m);
        }
    }

    private void compileMethod(Object target, Handler annotation, HandlerMethod entry, Method m) {
        requireHandlerMethod(m);
        int domain = entry.domain() != 0 ? entry.domain() : annotation.domain();
        if (!domains.contains(domain)) throw invalid("Unregistered handler domain: " + m);
        Class<?> contextType = null, messageType = null;
        int[] sources = new int[m.getParameterCount()];
        List<HandlerArgumentBinding<?>> custom = new ArrayList<>();
        Set<Class<?>> customTypes = new HashSet<>();
        for (int i = 0; i < sources.length; i++) {
            Class<?> type = m.getParameterTypes()[i];
            if (Context.class.isAssignableFrom(type)) {
                if (contextType != null || !(type.isAssignableFrom(HandlerContext.class) || HandlerContext.class.isAssignableFrom(type)))
                    throw invalid("Invalid handler context: " + m);
                contextType = type;
                sources[i] = FROM_CONTEXT;
            } else if (protocols.get(type) != null) {
                if (messageType != null) throw invalid("Multiple message parameters: " + m);
                messageType = type;
                sources[i] = FROM_MESSAGE;
            } else {
                var argument = argumentBindings.get(type);
                if (argument == null) throw invalid("Unregistered Handler argument: " + type.getName() + " at " + m);
                if (!customTypes.add(type)) throw invalid("Repeated Handler argument type: " + type.getName());
                custom.add(argument);
                sources[i] = FROM_ARGUMENTS;
            }
        }
        if (messageType == null) throw invalid("Handler needs one registered message: " + m);
        ProtocolDescriptor protocol = protocols.get(messageType);
        if (protocol.type() == ProtocolDescriptor.Type.RESPONSE)
            throw invalid("Response cannot be an inbound handler: " + m);
        boolean returnsResult = m.getReturnType() != void.class;
        if (returnsResult && protocol.responseType() == null)
            throw invalid("A Handler that returns a response needs a message with a registered response: " + m);
        if (returnsResult && m.getReturnType() != protocol.responseType())
            throw invalid("Handler must return the message's registered response "
                    + protocol.responseType().getName() + ": " + m);
        contextType = answeringContext(contextType, returnsResult, m);
        registerRouteKey(annotation, entry, messageType);
        HandlerBinding binding = new HandlerBinding(domain, contextType, List.copyOf(custom), returnsResult,
                adapt(bind(target, m), sources, returnsResult));
        if (handlers.putIfAbsent(messageType, binding) != null) throw invalid("Duplicate handler " + messageType);
    }

    /**
     * A Handler whose return value is its response requires a client context at dispatch, so a declared
     * supertype is tightened to {@link ClientHandlerContext} and a sibling transport context is rejected.
     */
    private static Class<?> answeringContext(Class<?> declared, boolean returnsResult, Method m) {
        if (!returnsResult) return declared;
        if (declared == null || declared.isAssignableFrom(ClientHandlerContext.class)) return ClientHandlerContext.class;
        if (ClientHandlerContext.class.isAssignableFrom(declared)) return declared;
        throw invalid("A Handler that returns a response needs a ClientHandlerContext: " + m);
    }

    /** The method's RouteKey rule overrides the class rule. */
    private void registerRouteKey(Handler annotation, HandlerMethod entry, Class<?> messageType) {
        requireKeyRule(entry.routeKey(), entry.routeKeyMethod(), "Handler");
        boolean overrideKey = !entry.routeKey().isEmpty() || !entry.routeKeyMethod().isEmpty();
        String keyField = overrideKey ? entry.routeKey() : annotation.routeKey();
        String keyMethod = overrideKey ? entry.routeKeyMethod() : annotation.routeKeyMethod();
        RouteKeyBinding<?> key = !keyField.isEmpty() ? RouteKeyBinding.ofField(messageType, keyField)
                : !keyMethod.isEmpty() ? RouteKeyBinding.ofMethod(messageType, keyMethod) : null;
        if (key != null && routeKeys.putIfAbsent(messageType, key) != null)
            throw invalid("Duplicate route-key binding: " + messageType.getName());
    }

    /**
     * Adapts the bound method to {@code (Object context, Object message, Object[] customArguments) -> Object}.
     * A void method yields a constant null result, which Runtime never sends.
     */
    private static MethodHandle adapt(MethodHandle handle, int[] sources, boolean returnsResult) {
        if (!returnsResult) handle = MethodHandles.filterReturnValue(handle, MethodHandles.constant(Object.class, null));
        handle = handle.asType(MethodType.genericMethodType(sources.length));
        int customIndex = 0;
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] != FROM_ARGUMENTS) continue;
            MethodHandle getter = MethodHandles.insertArguments(
                    MethodHandles.arrayElementGetter(Object[].class), 1, customIndex++);
            handle = MethodHandles.filterArguments(handle, i, getter);
        }
        return MethodHandles.permuteArguments(handle,
                MethodType.methodType(Object.class, Object.class, Object.class, Object[].class), sources);
    }
}
