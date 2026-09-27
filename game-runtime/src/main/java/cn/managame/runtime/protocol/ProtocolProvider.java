package cn.managame.runtime.protocol;

@FunctionalInterface
public interface ProtocolProvider { void register(ProtocolRegistrar registrar); }
