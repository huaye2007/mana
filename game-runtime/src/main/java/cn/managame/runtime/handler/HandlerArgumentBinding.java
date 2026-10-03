package cn.managame.runtime.handler;

import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.HandlerContext;

import java.util.Objects;
import java.util.function.Function;

/**
 * Application-defined argument projection from the integration-supplied HandlerContext.
 * The resolver runs on the submitting thread before Route admission. It must be thread-safe,
 * must not access Route-confined state, and must return a non-null value of the registered type.
 */
public record HandlerArgumentBinding<T>(Class<T> type, Function<HandlerContext, ? extends T> resolver) {
    public HandlerArgumentBinding {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(resolver, "resolver");
        if (type.isPrimitive() || Context.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Custom Handler argument must be a non-Context reference type");
        }
    }

    public static <T> HandlerArgumentBinding<T> of(Class<T> type, Function<HandlerContext, ? extends T> resolver) {
        return new HandlerArgumentBinding<>(type, resolver);
    }

    public T resolve(HandlerContext context) {
        return type.cast(Objects.requireNonNull(resolver.apply(Objects.requireNonNull(context, "context")),
                "Handler argument resolver returned null"));
    }
}
