package cn.managame.jpa.core.metadata;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small reflection helpers shared by metadata resolvers and executors.
 */
public final class ReflectionUtils {

    /** Cached no-arg constructor handles: the row-mapping hot path instantiates one
     * entity per row, so the reflective constructor lookup is resolved once per class. */
    private static final Map<Class<?>, MethodHandle> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    private ReflectionUtils() {}

    /**
     * 获取类及其所有父类的声明字段（不含 Object）
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    public static boolean isPersistentField(Field field) {
        int modifiers = field.getModifiers();
        return !field.isSynthetic()
                && !Modifier.isStatic(modifiers)
                && !Modifier.isTransient(modifiers);
    }

    /**
     * 创建无参实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T newInstance(Class<?> clazz) {
        MethodHandle constructor = CONSTRUCTOR_CACHE.computeIfAbsent(clazz, ReflectionUtils::resolveConstructor);
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
