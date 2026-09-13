package cn.managame.runtime.diagnostics;

@FunctionalInterface
public interface HandlerExceptionHandler { void handle(HandlerException exception); }
