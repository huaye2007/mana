package cn.managame.runtime.executor;

import java.util.*;
public record RouteExecutorBinding(RouteExecutor executor, List<Integer> routeDomains) {
    public RouteExecutorBinding { Objects.requireNonNull(executor); routeDomains = List.copyOf(routeDomains); if (routeDomains.isEmpty()) throw new IllegalArgumentException("Empty binding"); }
    public static RouteExecutorBinding of(RouteExecutor executor, int... domains) {
        return new RouteExecutorBinding(executor, Arrays.stream(domains).boxed().toList());
    }
}
