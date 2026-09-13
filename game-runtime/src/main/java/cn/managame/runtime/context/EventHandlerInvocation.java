package cn.managame.runtime.context;


import java.util.Objects;

/**
 * @deprecated Events are published directly through {@link GameRuntime#publish(Object)};
 * event methods receive the event and do not use parameter resolvers.
 */
@Deprecated(forRemoval = true)
public record EventHandlerInvocation(Object event, Metadata metadata) implements HandlerInvocation {
    public EventHandlerInvocation { Objects.requireNonNull(event); Objects.requireNonNull(metadata); }
}
