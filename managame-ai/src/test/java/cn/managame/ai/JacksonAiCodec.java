package cn.managame.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;

/** Test fixture showing how an application binds {@link AiJsonCodec} to Jackson in three lines. */
public final class JacksonAiCodec implements AiJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String encode(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    @Override public Object decode(String json, Type type) throws Exception {
        return mapper.readValue(json, mapper.constructType(type));
    }
}
