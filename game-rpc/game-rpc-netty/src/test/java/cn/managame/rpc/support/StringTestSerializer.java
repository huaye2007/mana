package cn.managame.rpc.support;

import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationType;

import java.nio.charset.StandardCharsets;

/**
 * 测试用：String <-> UTF-8 bytes，占用 JSON 的 serialType 槽位。
 */
public class StringTestSerializer implements ISerializer {

    @Override
    public SerializationType type() {
        return SerializationType.JSON;
    }

    @Override
    public <T> byte[] serialize(T value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] array, Class<T> type) {
        return type.cast(new String(array, StandardCharsets.UTF_8));
    }
}
