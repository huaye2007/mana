package cn.managame.data.codec;
import java.lang.reflect.Type;
public interface BinaryCodec {
    byte[] encode(Object value);
    Object decode(byte[] value, Type type);
}
