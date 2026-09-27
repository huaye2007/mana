package cn.managame.core;

public interface MetadataBuilder {
    <T> MetadataBuilder put(MetadataKey<T> key, T value);
    Metadata build();
}
