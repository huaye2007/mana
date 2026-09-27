package cn.managame.runtime.route;

@FunctionalInterface
public interface RouteKeyExtractor<T> { long extract(T message); }
