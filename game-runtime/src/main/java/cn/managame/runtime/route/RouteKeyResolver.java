package cn.managame.runtime.route;

@FunctionalInterface
public interface RouteKeyResolver<T> { long resolve(T source); }
