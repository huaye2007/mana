package com.github.huaye2007.mana.jpa.core.metadata;

import java.lang.reflect.Field;

/**
 * 通用字段元数据接口。
 */
public interface FieldMetadata {

    /**
     * Java 反射字段
     */
    Field javaField();

    /**
     * 属性名
     */
    String propertyName();

    /**
     * 存储名（列名 / field name / key 等）
     */
    String storageName();

    /**
     * Java 类型
     */
    Class<?> javaType();

    /**
     * 预热的字段访问器（启动期构建，运行期高性能读写）
     */
    FieldAccessor accessor();
}
