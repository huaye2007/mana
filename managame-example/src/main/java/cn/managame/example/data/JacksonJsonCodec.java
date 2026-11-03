package cn.managame.example.data;

import cn.managame.data.mysql.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;

/** 实体里集合、Map 这类字段和 JSON 列之间的转换，用 Jackson。线程安全：ObjectMapper 配好后可多线程共用。 */
public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override public Object decode(String json, Type type) {
        try {
            return mapper.readValue(json, mapper.constructType(type));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
