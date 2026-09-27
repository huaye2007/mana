package cn.managame.runtime.protocol;

public interface ProtocolRegistry { ProtocolDescriptor<?> get(ProtocolType type, int command); ProtocolDescriptor<?> get(Class<?> messageType); Class<?> getResponseType(Class<?> requestType); }
