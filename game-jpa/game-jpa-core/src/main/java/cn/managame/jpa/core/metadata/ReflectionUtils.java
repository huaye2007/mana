package cn.managame.jpa.core.metadata;

import cn.managame.common.reflect.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * JPA 元数据解析器/执行器用到的反射助手。
 * <p>
 * 通用的 {@link #getAllFields}/{@link #newInstance} 已下沉到 {@link Reflection}，此处转发以
 * 兼容既有调用点；{@link #isPersistentField} 属 JPA 持久化语义，保留在本模块。
 */
public final class ReflectionUtils {

    private ReflectionUtils() {}

    /**
     * 获取类及其所有父类的声明字段（不含 Object）
     *
     * @see Reflection#getAllFields(Class)
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        return Reflection.getAllFields(clazz);
    }

    public static boolean isPersistentField(Field field) {
        int modifiers = field.getModifiers();
        return !field.isSynthetic()
                && !Modifier.isStatic(modifiers)
                && !Modifier.isTransient(modifiers);
    }

    /**
     * 创建无参实例
     *
     * @see Reflection#newInstance(Class)
     */
    public static <T> T newInstance(Class<?> clazz) {
        return Reflection.newInstance(clazz);
    }
}
