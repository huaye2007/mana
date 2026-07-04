package cn.managame.jpa.rdb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 索引声明。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Indexes.class)
public @interface Index {

    /**
     * 索引名
     */
    String name();

    /**
     * 索引列名列表
     */
    String[] columns();

    /**
     * 是否唯一索引
     */
    boolean unique() default false;
}
