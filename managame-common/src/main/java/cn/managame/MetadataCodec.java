package cn.managame;

public interface MetadataCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes, int offset, int length);
}
