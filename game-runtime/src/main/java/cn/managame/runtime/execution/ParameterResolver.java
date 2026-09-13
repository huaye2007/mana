package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;

/**
 * Resolves one command semantic value. Ordinary registrations execute inside the route.
 * Routing identities must explicitly opt in with registerRouteSource.
 */
@FunctionalInterface
public interface ParameterResolver<T> {
    T resolve(CommandHandlerInvocation invocation);
}
