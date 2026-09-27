package cn.managame.data.codec;
import java.lang.reflect.Type;
public interface JsonCodec {
    String encode(Object value);
    Object decode(String value, Type type);
}
