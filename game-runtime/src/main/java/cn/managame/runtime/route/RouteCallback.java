package cn.managame.runtime.route;

public interface RouteCallback<T> { void onSuccess(T result); void onFail(int frameworkErrorCode); }
