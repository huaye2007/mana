package cn.managame.data.mysql;

import java.lang.reflect.Type;

/**
 * Turns fields that are not scalars (collections, maps, objects, enums) into JSON columns and back. The application
 * provides it, for example over Jackson; it is called from write and load threads at once.
 */
public interface JsonCodec {
    String encode(Object value);

    /**
     * @param type the field's {@link cn.managame.data.spi.EntityField#valueType()}, such as
     *             {@code ConcurrentHashMap<Integer, Long>} for {@code Map<Integer, Long> items = new ConcurrentHashMap<>()}
     */
    Object decode(String json, Type type);
}
