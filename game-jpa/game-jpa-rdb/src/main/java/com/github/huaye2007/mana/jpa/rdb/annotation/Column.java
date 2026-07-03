package com.github.huaye2007.mana.jpa.rdb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段到列的映射。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    /**
     * 列名，默认使用字段名
     */
    String name() default "";

    /**
     * 列类型，取值见 {@link ColumnType}。
     * <ul>
     *   <li>{@code ""}（默认）—— 按字段 Java 类型推断；复杂类型默认 {@link ColumnType#JSON}。</li>
     *   <li>{@link ColumnType#JSON} —— JSON 列，触发对象 ↔ JSON 字符串序列化。</li>
     *   <li>其他取值 —— 原样作为 DDL 列类型，长度取 {@link #length()}；
     *       值绑定走 {@code TypeConverter}（自定义类型在此挂载转换器）。</li>
     * </ul>
     */
    String type() default "";

    /**
     * 字符串长度
     */
    int length() default 255;

    /**
     * 列默认值。空字符串表示不声明默认值。
     */
    String defaultValue() default "";
}
