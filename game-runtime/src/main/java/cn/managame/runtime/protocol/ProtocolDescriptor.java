package cn.managame.runtime.protocol;

public interface ProtocolDescriptor<T> { ProtocolType type(); int command(); Class<T> messageType(); }
