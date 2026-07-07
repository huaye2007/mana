package cn.managame.common.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用反射助手：取类的全部声明字段、缓存无参构造并实例化。
 * <p>
 * 从 game-jpa 的 {@code ReflectionUtils} 下沉而来，供任意模块复用；持久化语义
 * （如 {@code isPersistentField}）仍留在 game-jpa。构造器句柄按类缓存，行映射热路径
 * 每行一次实例化只解析一次构造器。零依赖。
 */
public final class Reflection {

    /** 缓存无参构造句柄：行映射热路径每行实例化一个对象，构造器只解析一次。 */
    private static final Map<Class<?>, MethodHandle> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    private Reflection() {
    }

    /** 类及其所有父类（不含 {@link Object}）的声明字段。 */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    /** 通过缓存的无参构造创建实例。 */
    @SuppressWarnings("unchecked")
    public static <T> T newInstance(Class<?> clazz) {
        MethodHandle constructor = CONSTRUCTOR_CACHE.computeIfAbsent(clazz, Reflection::resolveConstructor);
        try {
            return (T) constructor.invoke();
        } catch (Throwable e) {
            throw new RuntimeException("Cannot instantiate: " + clazz.getName(), e);
        }
    }

    private static MethodHandle resolveConstructor(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(constructor);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Cannot instantiate: " + clazz.getName(), e);
        }
    }
}
