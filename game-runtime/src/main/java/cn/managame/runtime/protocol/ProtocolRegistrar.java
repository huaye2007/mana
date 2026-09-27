package cn.managame.runtime.protocol;

public interface ProtocolRegistrar { void register(ProtocolDescriptor<?> descriptor); void bindResponse(Class<?> requestType, Class<?> responseType); }
