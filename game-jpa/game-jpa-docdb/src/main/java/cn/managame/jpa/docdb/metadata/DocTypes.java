package cn.managame.jpa.docdb.metadata;

import java.lang.reflect.Modifier;

/**
 * 文档字段类型判定。
 * <p>
 * 嵌套文档不需要注解标记：可实例化的业务 POJO 字段默认按嵌套文档（embedded document）映射，
 * 与 RDB 侧「复杂类型默认按 JSON 列存储」的约定对齐。JDK 类型、枚举、数组、接口/抽象类
 * （无法反射实例化回读）与 BSON 原生类型走 TypeConverter / 驱动原生编码。
 * 注册了自定义 {@code TypeConverter} 的类型在执行器写读时优先走转换器。
 */
public final class DocTypes {

    private DocTypes() {
    }

    /** 该 Java 类型是否按嵌套文档映射。 */
    public static boolean isEmbeddedDocument(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()
                || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.")
                && !name.startsWith("jdk.") && !name.startsWith("org.bson.");
    }
}
