package cn.managame.runtime.error;

@FunctionalInterface
public interface RuntimeErrorHandler { void onError(RuntimeError error); }
