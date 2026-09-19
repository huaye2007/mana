package cn.managame.core.mapping;

import java.lang.reflect.Type;

/** Optional JSON codec used for collection/map/custom-object fields. */
public interface JsonCodec {
    String encode(Object value, Type declaredType);
    Object decode(String json, Type declaredType);
}
